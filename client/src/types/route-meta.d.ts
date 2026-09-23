import 'vue-router'

declare module 'vue-router' {
  interface RouteMeta {
    /** 独立页面布局（登录页）：不渲染全局导航外壳 */
    plain?: boolean
  }
}
