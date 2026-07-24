package com.example.ilink.feature.food;

import com.example.ilink.feature.travel.AmapService;

import java.net.http.HttpClient;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 生成平台搜索入口，并在可靠匹配到门店 ID 时升级为门店直达链接。 */
public final class FoodOrderService {

    private final ElemeDataService elemeDataService;
    private final MeituanDataService meituanDataService;

    public FoodOrderService() {
        this(HttpClient.newHttpClient());
    }

    public FoodOrderService(HttpClient client) {
        this(new ElemeDataService(client), new MeituanDataService(client));
    }

    FoodOrderService(ElemeDataService elemeDataService, MeituanDataService meituanDataService) {
        this.elemeDataService = elemeDataService;
        this.meituanDataService = meituanDataService;
    }

    public String generateLinks(String restaurantNames) {
        if (restaurantNames == null || restaurantNames.isBlank()) return "";

        List<String> names = Arrays.stream(restaurantNames.split("[,，、]"))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
        if (names.isEmpty()) return "";

        StringBuilder result = new StringBuilder();
        for (String name : names) {
            if (!result.isEmpty()) result.append("\n\n");
            result.append(name).append("：\n")
                    .append("饿了么点餐：").append(LinkShortener.elemeUrl(name)).append('\n')
                    .append("美团点餐：").append(LinkShortener.meituanUrl(name));
        }
        return result.toString();
    }

    public ResolvedStoreLinks resolveStore(AmapService.Restaurant store) {
        CompletableFuture<String> elemeId = CompletableFuture.supplyAsync(() ->
                elemeDataService.findStoreId(store.name(), store.longitude(), store.latitude()));
        CompletableFuture<String> meituanId = CompletableFuture.supplyAsync(() ->
                meituanDataService.findStoreId(store.name(), store.longitude(), store.latitude()));

        String elemeStoreId = elemeId.join();
        String meituanStoreId = meituanId.join();
        PlatformStoreLink eleme = elemeStoreId.isBlank()
                ? new PlatformStoreLink("饿了么", LinkShortener.elemeUrl(store.name()), false)
                : new PlatformStoreLink("饿了么", LinkShortener.elemeStoreUrl(elemeStoreId), true);
        PlatformStoreLink meituan = meituanStoreId.isBlank()
                ? new PlatformStoreLink("美团", LinkShortener.meituanUrl(store.name()), false)
                : new PlatformStoreLink("美团", LinkShortener.meituanStoreUrl(meituanStoreId), true);
        return new ResolvedStoreLinks(store, eleme, meituan);
    }

    public String formatStoreLinks(ResolvedStoreLinks links) {
        AmapService.Restaurant store = links.store();
        return "已选择：" + store.name() + "\n"
                + "地址：" + (store.address().isBlank() ? "以平台页面为准" : store.address()) + "\n\n"
                + format(links.eleme()) + "\n"
                + format(links.meituan()) + "\n\n"
                + "“门店直达”表示已匹配平台门店 ID；“精确搜索”会使用完整分店名称搜索。";
    }

    private String format(PlatformStoreLink link) {
        return link.platform() + (link.direct() ? "门店直达：" : "分店精确搜索：") + link.url();
    }

    public record ResolvedStoreLinks(AmapService.Restaurant store,
                                     PlatformStoreLink eleme,
                                     PlatformStoreLink meituan) { }
}
