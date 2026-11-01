import { ElMessageBox } from 'element-plus'

/**
 * 危险操作二次确认：确认按钮使用危险色并明确写出动作，默认焦点停在取消上，
 * 取消或关闭返回 false，不抛出异常。
 */
export async function confirmDanger(title: string, message: string, confirmButtonText: string, cancelButtonText = '取消') {
  try {
    await ElMessageBox.confirm(message, title, {
      type: 'warning', confirmButtonText, cancelButtonText, autofocus: false,
      confirmButtonClass: 'el-button--danger', customClass: 'sv-danger-confirm', distinguishCancelAndClose: false,
    })
    return true
  } catch { return false }
}
