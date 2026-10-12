/**
 * 手机端安装包下载二维码(android-release.yml 发版流水线的配套入口)。
 *
 * 地址指向 GitHub Releases 的固定资产 LyricCast.apk:流水线每次发版都以同名
 * 资产上传到最新 Release,而 releases/latest/download/<file> 是 GitHub 的
 * 永久重定向,始终落到最新版本——二维码内容不随版本变化,无需重印。
 */
import qrcode from 'qrcode-generator'

export const APK_DOWNLOAD_URL =
  'https://github.com/Particaly/LyricCast/releases/latest/download/LyricCast.apk'

/** 生成下载二维码 SVG(scalable,尺寸由 CSS 控制);内容超长等异常时返回空串。 */
export function buildDownloadQrSvg(payload: string = APK_DOWNLOAD_URL): string {
  try {
    const qr = qrcode(0, 'M')
    qr.addData(payload)
    qr.make()
    return qr.createSvgTag({ cellSize: 4, margin: 0, scalable: true })
  } catch {
    return ''
  }
}
