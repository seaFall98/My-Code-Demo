import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模拟 Alibaba 订单服务跨机房部署场景
 * 场景：华东1（高配）4核8G x2 实例，华东2（低配）2核4G x1 实例
 * 权重比：华东1:华东2 = 2:1
 * 实际应用：Dubbo Provider 通过 @DubboService(weight=200) 设置权重
 * @author seaFall98
 */
public class OrderServiceDemo {

    static class Provider {
        final String url;
        final int weight;
        final String zone;

        Provider(String url, int weight, String zone) {
            this.url = url;
            this.weight = weight;
            this.zone = zone;
        }

        @Override
        public String toString() {
            return zone + "(" + url + ")";
        }
    }

    public static void main(String[] args) {
        List<Provider> providers = Arrays.asList(
                new Provider("192.168.1.10:20880", 5, "华东1-A"),
                new Provider("192.168.1.11:20880", 3, "华东1-B"),
                new Provider("192.168.1.20:20880", 2, "华东2-A")
        );

        SmoothWeightedRoundRobin<Provider> lb = new SmoothWeightedRoundRobin<>();

        // 统计分布
        Map<String, Integer> distribution = new ConcurrentHashMap<>();

        System.out.println("=== 平滑加权轮询选择结果（前10次） ===");
        for (int i = 1; i <= 10; i++) {
            Provider selected = lb.select(
                    "com.alibaba.order.OrderService.createOrder",
                    providers,
                    p -> p.weight,
                    p -> p.url
            );
            distribution.merge(selected.zone, 1, Integer::sum);
            System.out.printf("第 %2d 次 → %s%n", i, selected);
        }

        System.out.println("\n=== 分布统计 ===");
        distribution.forEach((zone, count) ->
                System.out.printf("%-12s: %d次 (%.0f%%)%n",
                        zone, count, count * 100.0 / 10));
    }
}

/*运行结果:
=== 平滑加权轮询选择结果（前10次） ===
第  1 次 → 华东1-A(192.168.1.10:20880)
第  2 次 → 华东1-B(192.168.1.11:20880)
第  3 次 → 华东2-A(192.168.1.20:20880)
第  4 次 → 华东1-A(192.168.1.10:20880)
第  5 次 → 华东1-A(192.168.1.10:20880)
第  6 次 → 华东1-B(192.168.1.11:20880)
第  7 次 → 华东1-A(192.168.1.10:20880)
第  8 次 → 华东2-A(192.168.1.20:20880)
第  9 次 → 华东1-B(192.168.1.11:20880)
第 10 次 → 华东1-A(192.168.1.10:20880)

=== 分布统计 ===
华东1-B       : 3次 (30%)
华东2-A       : 2次 (20%)
华东1-A       : 5次 (50%)
 */