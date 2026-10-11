import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue()],
  base: './',
  build: {
    outDir: 'src-ztools/dist',
    emptyOutDir: true,
    rollupOptions: {
      // 多页:main = 插件主界面,desktop = 桌面悬浮歌词窗口(desktop-bridge 用 createBrowserWindow 打开)
      input: {
        main: 'index.html',
        desktop: 'desktop.html'
      }
    }
  }
})
