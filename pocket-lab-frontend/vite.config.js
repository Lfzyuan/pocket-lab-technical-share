import {readFileSync,statSync} from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'
// Preserve the browser Host for same-origin /api requests in local development.
// Rewriting Host alone makes Spring see a cross-origin request and reject login.
export default defineConfig({
  publicDir: fileURLToPath(new URL('./public', import.meta.url)),
  server: { proxy: { '/api': { target: process.env.API_PROXY_TARGET || 'http://127.0.0.1:8080', changeOrigin: false } } },
  plugins: [
    {name:'preserve-live-public-assets',configureServer(server){server.middlewares.use((req,res,next)=>{if(req.method!=='GET')return next();let url;try{url=decodeURIComponent(new URL(req.url,'http://localhost').pathname);}catch{return next();}if(!/^\/(theme|features)\//.test(url))return next();const root=fileURLToPath(new URL('./public',import.meta.url)),file=path.resolve(root,'.'+url);if(!file.startsWith(root+path.sep))return next();try{if(!statSync(file).isFile())return next();res.setHeader('Content-Type',file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':file.endsWith('.svg')?'image/svg+xml':'application/octet-stream');res.end(readFileSync(file));}catch{next();}});}},
    uni(),
  ],
})
