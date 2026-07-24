package com.example.ilink.app;

import com.example.ilink.config.Config;
import com.example.ilink.conversation.PlanSessionStore;
import com.example.ilink.conversation.UserSessionStore;
import com.example.ilink.feature.calendar.CalendarService;
import com.example.ilink.feature.calendar.HolidayService;
import com.example.ilink.feature.planning.TodoService;
import com.example.ilink.feature.memory.MemoryService;
import com.example.ilink.feature.mail.QqMailService;
import com.example.ilink.feature.weather.WeatherLocation;
import com.example.ilink.feature.weather.WeatherService;
import com.example.ilink.model.CalendarEvent;
import com.example.ilink.model.PlanTask;
import com.example.ilink.model.ReminderDelivery;
import com.example.ilink.model.TaskPlan;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将登录时的天气、节日、日历、计划、待办和离线提醒合成一份温柔简报。 */
public final class LoginBriefingService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("M月d日，EEEE");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private static final Pattern CURRENT_TEMPERATURE = Pattern.compile("当前温度：(-?\\d+(?:\\.\\d+)?)℃");

    private final WeatherService weatherService;
    private final CalendarService calendarService;
    private final TodoService todoService;
    private final PlanSessionStore planSessions;
    private final UserSessionStore userSessions;
    private final HolidayService holidayService;
    private final MemoryService memoryService;
    private final QqMailService qqMailService;

    public LoginBriefingService(WeatherService weatherService, CalendarService calendarService,
                                TodoService todoService, PlanSessionStore planSessions,
                                 UserSessionStore userSessions, HolidayService holidayService,
                                 MemoryService memoryService, QqMailService qqMailService) {
        this.weatherService = weatherService;
        this.calendarService = calendarService;
        this.todoService = todoService;
        this.planSessions = planSessions;
        this.userSessions = userSessions;
        this.holidayService = holidayService;
        this.memoryService = memoryService;
        this.qqMailService = qqMailService;
    }

    public String build(String userId, List<ReminderDelivery> overdueDeliveries) {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        StringBuilder text = new StringBuilder(greeting(now.getHour())).append("，欢迎回来。\n\n")
                .append("今天是").append(today.format(DATE_FORMAT)).append("。")
                .append(holidayService.describe(today)).append('\n');

        String weather = loadWeather(userId);
        if (!weather.isBlank()) {
            text.append('\n').append(weather).append('\n')
                    .append(buildTravelAdvice(weather)).append('\n')
                    .append(buildOutfitAdvice(userId, weather)).append('\n');
        }

        if (!overdueDeliveries.isEmpty()) {
            text.append("\n你不在的时候，有几件事没能及时提醒到你，我现在轻轻补上：\n");
            for (ReminderDelivery delivery : overdueDeliveries) {
                CalendarEvent event = calendarService.getEvent(delivery.eventId());
                if (event != null) {
                    text.append("- ").append(event.title()).append("（原提醒时间：")
                            .append(delivery.scheduledAt().format(TIME_FORMAT)).append("）\n");
                }
            }
        }

        List<CalendarEvent> events = calendarService.eventsForDay(userId, today);
        if (!events.isEmpty()) {
            text.append("\n今天还有这些日历安排，我替你一起记着：\n");
            for (CalendarEvent event : events) {
                text.append("- ").append(event.startAt().toLocalTime().withSecond(0).withNano(0))
                        .append(' ').append(event.title()).append('\n');
            }
        }

        String todoText = todoService.list(userId);
        if (!todoText.contains("没有待完成")) text.append('\n').append(todoText).append('\n');
        appendTodayPlan(text, userId, today);
        String mailBriefing = qqMailService == null ? "" : qqMailService.briefing(userId);
        if (!mailBriefing.isBlank()) text.append("\n\n").append(mailBriefing);

        return text.append("\n不用着急，一件一件来就好，我会继续帮你记着。")
                .toString().trim();
    }

    private String loadWeather(String userId) {
        String locationName = userSessions.getCurrentLocation(userId);
        if ((locationName == null || locationName.isBlank()) && memoryService != null) {
            locationName = memoryService.value(userId, "home_location");
        }
        if (locationName == null || locationName.isBlank()) locationName = Config.BRIEFING_DEFAULT_LOCATION;
        if (locationName == null || locationName.isBlank()) {
            return "我还不知道你所在的城市，告诉我以后，登录简报里就会带上当地天气。";
        }
        try {
            List<WeatherLocation> locations = weatherService.searchLocations(locationName);
            if (locations.isEmpty()) return "暂时没能查到你所在位置的天气。";
            return weatherService.queryWeather(locations.getFirst(), 0);
        } catch (Exception e) {
            return "天气服务暂时没有响应，出门前可以再留意一下实时天气。";
        }
    }

    private void appendTodayPlan(StringBuilder text, String userId, LocalDate today) {
        TaskPlan plan = planSessions.get(userId);
        if (plan == null) return;
        List<PlanTask> tasks = plan.tasks().stream()
                .filter(task -> today.toString().equals(task.scheduledDate()) && !"completed".equals(task.status()))
                .toList();
        if (tasks.isEmpty()) return;
        text.append("\n今天计划中的任务：\n");
        for (PlanTask task : tasks) text.append("- ").append(task.title()).append('\n');
    }

    private String buildTravelAdvice(String weather) {
        if (weather.matches("(?s).*(雨|雪|雷暴).*")) return "出行提醒：天气可能不太稳定，记得带伞，路上慢一点。";
        if (weather.contains("大风") || weather.matches("(?s).*风速：[2-9]\\d.*")) {
            return "出行提醒：今天风比较明显，骑行或步行时注意安全。";
        }
        return "出行提醒：天气看起来还算平稳，按自己的节奏出门就好。";
    }

    private String buildOutfitAdvice(String userId, String weather) {
        Matcher matcher = CURRENT_TEMPERATURE.matcher(weather);
        if (!matcher.find()) return "穿搭提醒：可以根据体感准备一件方便增减的外套。";
        double temperature = Double.parseDouble(matcher.group(1));
        String preference = memoryService == null ? "" : memoryService.value(userId, "temperature_preference");
        double adjusted = temperature + (preference.contains("怕冷") ? -3 : preference.contains("怕热") ? 3 : 0);
        if (adjusted >= 30) return "穿搭提醒：适合轻薄透气的短袖，注意防晒和补水。";
        if (adjusted >= 22) return "穿搭提醒：短袖或薄衬衫就可以，空调房里可带一件薄外套。";
        if (adjusted >= 15) return "穿搭提醒：建议长袖配一件轻外套，早晚会更舒服。";
        if (adjusted >= 5) return "穿搭提醒：天气偏凉，建议穿毛衣或厚外套。";
        return "穿搭提醒：今天比较冷，记得穿保暖外套，围巾也可以带上。";
    }

    private String greeting(int hour) {
        if (hour < 6) return "夜深了";
        if (hour < 12) return "早上好";
        if (hour < 18) return "下午好";
        return "晚上好";
    }
}
