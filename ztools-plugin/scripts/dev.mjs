/**
 * 同时启动插件 dev server 与 dist watch build。
 *
 * 开发模式下插件主界面走 http dev server 热更新,而桌面悬浮窗口固定加载
 * dist/desktop.html(ZTools createBrowserWindow 只接受相对路径/file://),
 * 所以改动源码后需要自动重建产物,悬浮窗才能吃到最新代码。
 *
 * 任一子进程退出都会结束另一进程;Ctrl+C 时两者一并终止。
 */
import { spawn } from 'node:child_process'

const jobs = [
  { name: 'server', args: ['vite'] },
  { name: 'watch', args: ['vite', 'build', '--watch'] }
]

const children = []
let shuttingDown = false

function shutdown(code = 0) {
  if (shuttingDown) return
  shuttingDown = true
  for (const child of children) {
    if (child.exitCode === null && !child.killed) child.kill('SIGTERM')
  }
  process.exit(code)
}

for (const job of jobs) {
  const child = spawn(job.args[0], job.args.slice(1), { stdio: 'inherit' })
  console.log(`[dev] ${job.name}: ${job.args.join(' ')}`)
  child.on('exit', (code) => {
    console.log(`[dev] ${job.name} 退出 (code=${code ?? 'signal'})`)
    shutdown(code ?? 1)
  })
  children.push(child)
}

process.on('SIGINT', () => shutdown(0))
process.on('SIGTERM', () => shutdown(0))
