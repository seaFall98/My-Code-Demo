import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 带缓存重建机制的一致性哈希负载均衡器
 * 对标 Dubbo AbstractLoadBalance + ConsistentHashLoadBalance 的缓存逻辑
 * @author seaFall98
 */
public class ConsistentHashLoadBalance<T> {

    // 每个 serviceKey 对应一个哈希环，避免不同服务互相干扰
    private final Map<String, ConsistentHashSelector<T>> selectors
            = new ConcurrentHashMap<>();

    public T select(String serviceKey,
                    List<T> invokers,
                    String requestKey,
                    java.util.function.Function<T, String> keyFunc) {

        // identityHashCode 是对 invoker 列表引用本身取哈希
        // invoker 上下线会导致列表引用变化，identityHashCode 随之变化
        // 这是 Dubbo 检测节点列表变更最轻量的方式，O(1)，无需逐一对比
        int currentHash = System.identityHashCode(invokers);

        ConsistentHashSelector<T> selector = selectors.get(serviceKey);

        if (selector == null || selector.getIdentityHashCode() != currentHash) {
            // invoker 列表发生变化（有节点上线或下线），重建哈希环
            // 注意：重建是 O(N * VIRTUAL_NODES * 4) 的操作，但这只在拓扑变化时触发，
            // 正常请求路由不涉及此分支
            selectors.put(serviceKey, new ConsistentHashSelector<>(invokers, keyFunc));
            selector = selectors.get(serviceKey);
        }

        return selector.select(requestKey);
    }
}