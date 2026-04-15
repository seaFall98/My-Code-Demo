import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 平滑加权轮询负载均衡
 * 对标 Dubbo RoundRobinLoadBalance 核心逻辑
 * 适用场景：网关层多实例流量分发、RPC 服务多节点调用
 * @author seaFall98
 */
public class SmoothWeightedRoundRobin<T> {

    /**
     * 每个节点的运行时权重状态
     * Dubbo 源码中对应 WeightedRoundRobin 内部类
     */
    static class WeightedNode {
        private final int weight;            // 配置权重（不变）
        private final AtomicLong current;    // 当前动态权重（线程安全）
        private volatile long lastUpdate;    // 最近一次更新时间戳（用于失效清理）

        WeightedNode(int weight) {
            this.weight = weight;
            this.current = new AtomicLong(0);
            this.lastUpdate = System.currentTimeMillis();
        }

        int getWeight() { return weight; }

        long increaseCurrent() {
            return current.addAndGet(weight);
        }

        void decreaseCurrent(long total) {
            current.addAndGet(-total);
        }

        long getCurrent() { return current.get(); }

        void touch() { this.lastUpdate = System.currentTimeMillis(); }
        long getLastUpdate() { return lastUpdate; }
    }

    // key: serviceKey + methodName，value: (url -> WeightedNode)
    // 对标 Dubbo 中的 methodWeightMap
    private final Map<String, Map<String, WeightedNode>> methodWeightMap
            = new ConcurrentHashMap<>();

    // 超过此时间未被调用的节点视为过期，清理防止内存泄漏
    private static final long RECYCLE_PERIOD = 60_000L;

    /**
     * 选择节点（核心方法）
     *
     * @param serviceKey 服务标识（如 com.xxx.OrderService.queryOrder）
     * @param invokers   可用节点列表，每个 T 对应一个 URL
     * @param weightFunc 从 invoker 获取权重的函数（对标 getWeight 方法）
     * @param keyFunc    从 invoker 获取唯一标识的函数（对标 url.toIdentityString）
     * @return 被选中的节点
     */
    public T select(String serviceKey,
                    List<T> invokers,
                    java.util.function.ToIntFunction<T> weightFunc,
                    java.util.function.Function<T, String> keyFunc) {

        if (invokers == null || invokers.isEmpty()) {
            return null;
        }
        if (invokers.size() == 1) {
            return invokers.get(0);
        }

        Map<String, WeightedNode> map = methodWeightMap
                .computeIfAbsent(serviceKey, k -> new ConcurrentHashMap<>());

        long totalWeight = 0;
        long maxCurrent = Long.MIN_VALUE;
        T selectedInvoker = null;
        WeightedNode selectedWRR = null;
        long now = System.currentTimeMillis();

        // ① 遍历所有节点：currentWeight += weight，同时找最大值
        for (T invoker : invokers) {
            String key = keyFunc.apply(invoker);
            int weight = weightFunc.applyAsInt(invoker);

            // 节点首次出现或权重变更时，重新创建（对标 Dubbo 的 weightChanged 判断）
            WeightedNode wrrNode = map.computeIfAbsent(key, k -> new WeightedNode(weight));
            wrrNode.touch();

            long cur = wrrNode.increaseCurrent(); // currentWeight += weight
            totalWeight += weight;

            // ② 找出 currentWeight 最大的节点
            if (cur > maxCurrent) {
                maxCurrent = cur;
                selectedInvoker = invoker;
                selectedWRR = wrrNode;
            }
        }

        // ③ 被选中节点：currentWeight -= totalWeight
        if (selectedWRR != null) {
            selectedWRR.decreaseCurrent(totalWeight);
        }

        // 清理长时间不活跃的节点（对标 Dubbo 的 cleanUp 逻辑，防止内存泄漏）
        if (map.size() > invokers.size()) {
            map.entrySet().removeIf(entry ->
                    now - entry.getValue().getLastUpdate() > RECYCLE_PERIOD);
        }

        return selectedInvoker;
    }
}