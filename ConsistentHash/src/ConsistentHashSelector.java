import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 一致性哈希负载均衡 —— 对标 Dubbo ConsistentHashLoadBalance
 * 核心数据结构：TreeMap 实现的有序哈希环
 *   key   = 虚拟节点哈希值（long，[0, 2^32)）
 *   value = 对应的真实 Invoker
 * 路由逻辑：TreeMap.tailMap(hash).firstKey()，即顺时针第一个节点
 * 时间复杂度：O(log N)，N = 虚拟节点总数
 * @author seaFall98
 */
public class ConsistentHashSelector<T> {

    // 哈希环：有序 Map，key 是虚拟节点位置，value 是真实节点
    private final TreeMap<Long, T> circle = new TreeMap<>();

    // 每个真实节点的虚拟节点数（Dubbo 默认 160，生成 160*4=640 个点）
    private static final int VIRTUAL_NODES = 160;

    // 用于检测 invoker 列表是否发生变更（节点上下线），决定是否重建环
    private final int identityHashCode;

    /**
     * 构建哈希环
     * 对每个 invoker 生成 VIRTUAL_NODES * 4 个虚拟节点
     */
    public ConsistentHashSelector(List<T> invokers,
                                  java.util.function.Function<T, String> keyFunc) {
        // 计算 invoker 列表的身份哈希，用于后续判断是否需要重建
        this.identityHashCode = System.identityHashCode(invokers);

        for (T invoker : invokers) {
            String address = keyFunc.apply(invoker);

            // 每次循环生成 4 个虚拟节点：MD5(address-i) 得到 16 字节，每 4 字节取一个 long
            for (int i = 0; i < VIRTUAL_NODES; i++) {
                // digest 是对 "address-i" 做 MD5，得到 16 字节
                byte[] digest = md5(address + "-" + i);

                // 从 16 字节中取 4 个哈希值，放入环
                // Dubbo 源码：for (int h = 0; h < 4; h++)
                for (int h = 0; h < 4; h++) {
                    long point = hash(digest, h);
                    circle.put(point, invoker);
                }
            }
        }
    }

    /**
     * 根据请求参数路由到具体节点
     * 顺时针找到第一个 >= requestHash 的虚拟节点
     */
    public T select(String requestKey) {
        byte[] digest = md5(requestKey);
        long requestHash = hash(digest, 0);
        return selectByHash(requestHash);
    }

    private T selectByHash(long hash) {
        // tailMap 返回所有 key >= hash 的子 Map
        SortedMap<Long, T> tail = circle.tailMap(hash);
        Long key = tail.isEmpty() ? circle.firstKey() : tail.firstKey();
        return circle.get(key);
    }

    int getIdentityHashCode() { return identityHashCode; }

    // ─── 工具方法 ─────────────────────────────────────────────────────────

    /**
     * 将 MD5 的 4 字节段转换为 long（无符号）
     * Dubbo 源码中的 hash(byte[] digest, int number) 方法*
     * 取 digest[4h]~digest[4h+3] 共4字节，拼成一个 unsigned int（用 long 存储避免符号问题）
     */
    private static long hash(byte[] digest, int number) {
        return (((long)(digest[3 + number * 4] & 0xFF) << 24)
                | ((long)(digest[2 + number * 4] & 0xFF) << 16)
                | ((long)(digest[1 + number * 4] & 0xFF) << 8)
                |  (long)(digest[    number * 4] & 0xFF))
                & 0xFFFFFFFFL;  // 确保结果在 [0, 2^32) 范围内
    }

    private static byte[] md5(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return md.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}