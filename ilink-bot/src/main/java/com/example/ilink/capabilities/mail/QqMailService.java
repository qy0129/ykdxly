package com.example.ilink.capabilities.mail;

import com.example.ilink.bootstrap.Config;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Flags;
import jakarta.mail.FetchProfile;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.FlagTerm;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** 通过 QQ 邮箱 IMAPS 执行只读查询，不修改邮件状态。 */
public final class QqMailService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private static final int FETCH_LIMIT = 50;
    private static final int DISPLAY_LIMIT = 5;
    private static final int BRIEFING_DISPLAY_LIMIT = 3;

    public boolean isConfigured() {
        return Config.QQ_MAIL_ENABLED
                && !Config.QQ_MAIL_ADDRESS.isBlank()
                && !Config.QQ_MAIL_AUTH_CODE.isBlank()
                && !Config.QQ_MAIL_OWNER_USER_ID.isBlank();
    }

    public boolean canAccess(String userId) {
        return isConfigured() && Config.QQ_MAIL_OWNER_USER_ID.equals(userId);
    }

    public String query(String userId, String action, String keyword) {
        if (!isConfigured()) {
            return "QQ邮箱还没有完成配置。请在 config.properties 中填写邮箱地址、IMAP授权码和所属微信用户ID。";
        }
        if (!canAccess(userId)) return "这个邮箱只允许绑定它的微信用户查询。";
        try {
            List<MailMessageView> messages = load(action, keyword);
            if (messages.isEmpty()) return emptyText(action, keyword);
            return format(messages, action);
        } catch (Exception e) {
            System.err.println("[QQ邮箱] 查询失败: " + e.getMessage());
            return "QQ邮箱暂时连接失败，请检查是否已开启 IMAP、邮箱地址和授权码是否正确。";
        }
    }

    /** 登录简报只显示数量和主题，不输出完整正文。 */
    public String briefing(String userId) {
        if (!canAccess(userId)) return "";
        try {
            List<MailMessageView> unread = loadUnreadHeaders();
            if (unread.isEmpty()) return "邮箱里暂时没有未读邮件。";
            return formatBriefing(unread);
        } catch (Exception e) {
            System.err.println("[QQ邮箱简报] 查询失败: " + e.getMessage());
            return "邮箱暂时没有连接成功，稍后可以再查询。";
        }
    }

    /** 登录简报只预取邮件头，避免为最多 50 封邮件下载完整正文。 */
    private List<MailMessageView> loadUnreadHeaders() throws Exception {
        Properties properties = mailProperties();
        Session session = Session.getInstance(properties);
        try (Store store = session.getStore("imaps")) {
            store.connect(Config.QQ_MAIL_IMAP_HOST, Config.QQ_MAIL_IMAP_PORT,
                    Config.QQ_MAIL_ADDRESS, Config.QQ_MAIL_AUTH_CODE);
            try (Folder inbox = store.getFolder("INBOX")) {
                inbox.open(Folder.READ_ONLY);
                Message[] unread = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
                if (unread.length == 0) return List.of();

                Message[] recent = Arrays.copyOfRange(unread, Math.max(0, unread.length - FETCH_LIMIT), unread.length);
                FetchProfile profile = new FetchProfile();
                profile.add(FetchProfile.Item.ENVELOPE);
                profile.add(FetchProfile.Item.FLAGS);
                inbox.fetch(recent, profile);

                List<MailMessageView> messages = new ArrayList<>();
                for (int index = recent.length - 1; index >= 0; index--) {
                    messages.add(toHeaderView(recent[index]));
                }
                return List.copyOf(messages);
            }
        }
    }

    private List<MailMessageView> load(String action, String keyword) throws Exception {
        Properties properties = mailProperties();
        Session session = Session.getInstance(properties);
        try (Store store = session.getStore("imaps")) {
            store.connect(Config.QQ_MAIL_IMAP_HOST, Config.QQ_MAIL_IMAP_PORT,
                    Config.QQ_MAIL_ADDRESS, Config.QQ_MAIL_AUTH_CODE);
            try (Folder inbox = store.getFolder("INBOX")) {
                inbox.open(Folder.READ_ONLY);
                Message[] source = "unread".equals(action) || "important".equals(action)
                        ? inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false))
                        : recent(inbox);
                List<MailMessageView> messages = new ArrayList<>();
                for (int index = source.length - 1; index >= 0 && messages.size() < FETCH_LIMIT; index--) {
                    MailMessageView view = toView(source[index]);
                    if ("important".equals(action) && !view.important()) continue;
                    if ("search".equals(action) && !matches(view, keyword)) continue;
                    messages.add(view);
                }
                messages.sort(Comparator.comparing(MailMessageView::sentAt).reversed());
                return List.copyOf(messages);
            }
        }
    }

    private Message[] recent(Folder inbox) throws Exception {
        int count = inbox.getMessageCount();
        if (count <= 0) return new Message[0];
        return inbox.getMessages(Math.max(1, count - FETCH_LIMIT + 1), count);
    }

    private MailMessageView toView(Message message) throws Exception {
        MailMessageView header = toHeaderView(message);
        return new MailMessageView(header.from(), header.subject(), header.sentAt(), header.unread(),
                header.important(), shorten(extractText(message), 700));
    }

    private MailMessageView toHeaderView(Message message) throws Exception {
        String subject = message.getSubject() == null ? "（无主题）" : message.getSubject().trim();
        String from = formatAddresses(message.getFrom());
        LocalDateTime sentAt = message.getSentDate() == null ? LocalDateTime.now()
                : LocalDateTime.ofInstant(message.getSentDate().toInstant(), ZoneId.systemDefault());
        boolean unread = !message.isSet(Flags.Flag.SEEN);
        boolean important = message.isSet(Flags.Flag.FLAGGED) || isImportant(subject);
        return new MailMessageView(from, subject, sentAt, unread, important, "");
    }

    static String formatBriefing(List<MailMessageView> unread) {
        long importantCount = unread.stream().filter(MailMessageView::important).count();
        StringBuilder text = new StringBuilder("邮箱里有").append(unread.size()).append("封近期未读邮件");
        if (importantCount > 0) text.append("，其中").append(importantCount).append("封可能比较重要");
        text.append("：\n");
        unread.stream().limit(BRIEFING_DISPLAY_LIMIT).forEach(mail -> text.append("- ")
                .append(mail.subject()).append("（").append(mail.from()).append("）\n"));
        return text.toString().trim();
    }

    private Properties mailProperties() {
        Properties properties = new Properties();
        properties.setProperty("mail.store.protocol", "imaps");
        properties.setProperty("mail.imaps.ssl.enable", "true");
        properties.setProperty("mail.imaps.connectiontimeout", "10000");
        properties.setProperty("mail.imaps.timeout", "15000");
        return properties;
    }

    private String extractText(Part part) throws Exception {
        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) return "";
        if (part.isMimeType("text/plain")) return String.valueOf(part.getContent()).trim();
        if (part.isMimeType("text/html")) return cleanHtml(String.valueOf(part.getContent()));
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            List<String> texts = new ArrayList<>();
            for (int index = 0; index < multipart.getCount(); index++) {
                BodyPart bodyPart = multipart.getBodyPart(index);
                String value = extractText(bodyPart);
                if (!value.isBlank()) texts.add(value);
            }
            return String.join("\n", texts);
        }
        return "";
    }

    private String format(List<MailMessageView> messages, String action) {
        String heading = switch (action) {
            case "important" -> "近期重要邮件";
            case "search" -> "邮件搜索结果";
            default -> "近期未读邮件";
        };
        StringBuilder text = new StringBuilder(heading).append("：\n");
        for (int index = 0; index < Math.min(DISPLAY_LIMIT, messages.size()); index++) {
            MailMessageView mail = messages.get(index);
            text.append(index + 1).append(". ").append(mail.subject())
                    .append("\n发件人：").append(mail.from())
                    .append("\n时间：").append(mail.sentAt().format(TIME_FORMAT));
            if (!mail.bodySnippet().isBlank()) text.append("\n内容摘要：").append(mail.bodySnippet());
            if (index < Math.min(DISPLAY_LIMIT, messages.size()) - 1) text.append("\n\n");
        }
        return text.toString();
    }

    private boolean matches(MailMessageView mail, String keyword) {
        if (keyword == null || keyword.isBlank()) return true;
        String target = (mail.from() + " " + mail.subject() + " " + mail.bodySnippet()).toLowerCase(Locale.ROOT);
        return Arrays.stream(keyword.toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(value -> !value.isBlank())
                .allMatch(target::contains);
    }

    private boolean isImportant(String subject) {
        return subject.matches("(?i).*(重要|紧急|通知|截止|会议|账单|合同|面试|offer|验证码|安全提醒).*" );
    }

    private String formatAddresses(Address[] addresses) {
        if (addresses == null || addresses.length == 0) return "未知发件人";
        List<String> values = new ArrayList<>();
        for (Address address : addresses) {
            values.add(address instanceof InternetAddress internetAddress
                    ? internetAddress.toUnicodeString() : address.toString());
        }
        return String.join("、", values);
    }

    private String cleanHtml(String html) {
        return html.replaceAll("(?is)<style.*?</style>|<script.*?</script>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replaceAll("\\s+", " ").trim();
    }

    private String shorten(String value, int limit) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }

    private String emptyText(String action, String keyword) {
        return switch (action) {
            case "important" -> "近期没有发现需要特别留意的未读邮件。";
            case "search" -> "没有找到与“" + keyword + "”匹配的近期邮件。";
            default -> "邮箱里暂时没有未读邮件。";
        };
    }
}
