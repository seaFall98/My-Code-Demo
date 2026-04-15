import java.util.Arrays;
import java.util.List;

/**
 * 电商购物车服务路由示例
 *
 * 业务背景：购物车数据缓存在各节点本地内存（LocalCache），
 * 必须保证同一个 userId 的请求始终打到同一个节点，
 * 否则会出现购物车数据不一致（用户A在节点1加了商品，
 * 但下一次请求打到节点2，读到空购物车）。
 *
 * 这正是一致性哈希最典型的适用场景。
 * @author seaFall98
 */
public class CartServiceDemo {

    static class Provider {
        final String host;
        final int port;
        Provider(String host, int port) {
            this.host = host; this.port = port;
        }
        @Override public String toString() { return host + ":" + port; }
    }

    public static void main(String[] args) {
        List<Provider> providers = Arrays.asList(
                new Provider("10.0.1.10", 20880),  // 华东1-A
                new Provider("10.0.1.11", 20880),  // 华东1-B
                new Provider("10.0.2.10", 20880)   // 华东2-A
        );

        ConsistentHashLoadBalance<Provider> lb = new ConsistentHashLoadBalance<>();

        // ① 验证同一 userId 始终路由到同一节点（会话粘滞）
        System.out.println("=== 同一用户多次请求，路由稳定性验证 ===");
        String[] userIds = {"uid=10001", "uid=10002", "uid=99999", "uid=10001"};
        for (String uid : userIds) {
            Provider p = lb.select("CartService.add", providers, uid,
                    pv -> pv.host + ":" + pv.port);
            System.out.printf("%-15s → %s%n", uid, p);
        }

        // ② 模拟节点下线（华东1-B 故障）
        System.out.println("\n=== 华东1-B 下线后，受影响流量最小化验证 ===");
        List<Provider> degraded = Arrays.asList(providers.get(0), providers.get(2));

        int changed = 0;
        int total = 100;
        for (int i = 0; i < total; i++) {
            String key = "uid=" + (10000 + i);
            Provider before = lb.select("CartService.add", providers,  key, pv -> pv.host + ":" + pv.port);
            Provider after  = lb.select("CartService.add", degraded, key, pv -> pv.host + ":" + pv.port);
            if (!before.toString().equals(after.toString())) changed++;
        }
        System.out.printf("100 个用户中，路由节点发生变更的有 %d 个（约 %.0f%%）%n",
                changed, changed * 100.0 / total);
        System.out.println("（若用取模哈希，几乎全部 100 个用户的路由都会改变）");
    }
}