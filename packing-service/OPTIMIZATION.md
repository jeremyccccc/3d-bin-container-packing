# Packing optimization

## 高效率初始解寻找 (Heuristic Initial Seed)

为了给后续优化提供高质量起点，不要仅依赖默认的全量装载尝试。建议先采用“逐件剥离法”（从全量货物开始，若失败则按体积从大到小剔除最小单元并重试），直到找到单柜能够容纳的最大子集。以此作为首柜的初始状态，再开启后续的局部搜索。

## 多策略内层校验 (Multi-strategy Verification)

单柜可行性验证是瓶颈。在判断一个货物组合是否能装下时，必须内部遍历多种启发式排序（如体积、长边、底面积优先等）。只有当所有主流策略都失败时，才判定该组合不可行。

## 字典序评分准则 (Lexicographical Scoring)

评分函数需严格遵循：柜数最少、前置柜位利用率最高、前置柜位件数或重量最大。在对比方案时，应逐柜进行体积对比，确保第一柜装满的优先级高于全局平均分布。

## 搜索空间与性能平衡

局部搜索应包含由后向前移动和异型或异重货物交换。设置合理的熔断机制（如 5 秒或 N 轮无提升则退出），确保计算时间在生产环境下可接受。

## Current implementation

- The optimization layer is implemented only in `packing-service`; upstream `api` and `core` are unchanged.
- Single-container verification tries Plain, area-first Plain, FastLAFF, and LAFF in both container orientations.
- Existing layouts are preserved as obstacles so cargo from later containers can be inserted directly into free-space points before a full repack is attempted.
- Candidate allocations are evaluated in ascending removed-volume order.
- Complete plans are compared lexicographically by container count, then per-container load volume, item count, and weight.
- Packing and optimization share a five-second deadline and a 2,000-candidate limit.
