export function resolveAdminBasePath(pageUrl: string): string {
  return new URL('.', pageUrl).pathname
}

// 使用浏览器实际访问地址，兼容后端 /admin/ 与 Nginx 对外根路径。
export const adminBasePath = resolveAdminBasePath(window.location.href)
