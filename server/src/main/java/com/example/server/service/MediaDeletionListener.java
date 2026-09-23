package com.example.server.service;

/**
 * 媒体删除的业务联动接口。
 *
 * <p>删除一个媒体前，引用它的其他业务记录必须先把状态收敛，否则会留下永久等待的记录：
 * 例如一个仍在处理中的导入子项，其媒体行被删除后再也不会有状态推进，父任务会永远停在处理中。
 *
 * <p>因此 {@link MediaService#deleteOwnedMedia(Long, Long)} 在删除媒体行之前回调所有监听者。
 * 使用接口而不是直接依赖导入模块，是为了保持依赖方向：导入模块依赖媒体服务，媒体服务不反向依赖它
 * （与 {@link AnalysisLifecycleListener} 同一模式，D-047）。
 *
 * <p>实现必须满足：
 * <ul>
 *   <li>幂等：媒体可能被重复删除或本就处于终态，重复调用不得抛错；</li>
 *   <li>快速：本方法在删除请求线程内执行，不做网络或 AI 调用；</li>
 *   <li>不得反向删除媒体或清理对象存储——那是媒体服务的职责。</li>
 * </ul>
 */
public interface MediaDeletionListener {

    /**
     * 媒体行删除之前的回调。
     *
     * @param mediaId 待删除媒体主键
     * @param userId  媒体归属用户，实现方可用于归属校验或日志
     */
    void beforeMediaDeleted(Long mediaId, Long userId);
}
