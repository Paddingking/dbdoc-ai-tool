import { app, BrowserWindow, ipcMain } from 'electron';
import * as path from 'path';
import * as fs from 'fs';
import * as http from 'http';
import { spawn, ChildProcess } from 'child_process';
import * as yaml from 'yaml';
import { autoUpdater } from 'electron-updater';

let mainWindow: BrowserWindow | null = null;
let backendProcess: ChildProcess | null = null;

const isDev = process.env.NODE_ENV === 'development' || !app.isPackaged;
const BACKEND_HOST = '127.0.0.1';
const BACKEND_PORT = 8080;
const PING_URL = `http://${BACKEND_HOST}:${BACKEND_PORT}/api/ping`;

function createWindow(): void {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 960,
    minHeight: 600,
    title: 'DBDoc AI',
    backgroundColor: '#1a1a2e',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  if (isDev) {
    mainWindow.loadURL('http://localhost:5173');
    mainWindow.webContents.openDevTools();
  } else {
    mainWindow.loadFile(path.join(__dirname, '../dist/index.html'));
  }

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

/**
 * 在打包态下从 extraResources 定位后端 jar 并拉起；开发态跳过（由 start-backend.ps1 手动起）。
 * 返回 Promise：后端 /api/ping 就绪或超时后 resolve。
 */
function startBackend(): Promise<void> {
  if (isDev) return Promise.resolve();

  let jarPath: string | null = null;
  try {
    const backendDir = path.join(process.resourcesPath, 'backend');
    const jar = fs.readdirSync(backendDir).find((f) => f.endsWith('.jar'));
    if (!jar) {
      console.error('[dbdoc-ai] 未找到后端 jar，请确认打包时后端已构建');
      return Promise.resolve();
    }
    jarPath = path.join(backendDir, jar);
  } catch (e) {
    console.error('[dbdoc-ai] 定位后端 jar 失败', e);
    return Promise.resolve();
  }

  backendProcess = spawn('java', ['-jar', jarPath, '--server.address=' + BACKEND_HOST, '--server.port=' + BACKEND_PORT], {
    stdio: 'ignore',
    env: process.env,
  });
  backendProcess.on('error', (err) => console.error('[dbdoc-ai] 启动后端失败（确认系统已安装 Java）', err));

  return waitForBackend(PING_URL, 30000);
}

function waitForBackend(url: string, timeoutMs: number): Promise<void> {
  const start = Date.now();
  const probe = (): Promise<boolean> =>
    new Promise((resolve) => {
      const req = http.get(url, (res) => {
        res.resume();
        resolve(res.statusCode !== undefined && res.statusCode < 500);
      });
      req.on('error', () => resolve(false));
      req.setTimeout(2000, () => {
        req.destroy();
        resolve(false);
      });
    });

  return (async () => {
    while (Date.now() - start < timeoutMs) {
      if (await probe()) return;
      await new Promise((r) => setTimeout(r, 500));
    }
    console.warn('[dbdoc-ai] 后端健康检查超时，前端可能无法连接');
  })();
}

function stopBackend(): void {
  if (backendProcess) {
    try {
      backendProcess.kill();
    } catch {
      /* 忽略 */
    }
    backendProcess = null;
  }
}

ipcMain.handle('get-config', async () => {
  // 打包态下源码树不存在，直接回退默认；开发态读取 application.yml。
  const configPath = path.join(app.getAppPath(), '..', 'backend', 'src', 'main', 'resources', 'application.yml');
  try {
    const content = fs.readFileSync(configPath, 'utf-8');
    const config = yaml.parse(content);
    return {
      backendUrl: `http://${config?.server?.address || BACKEND_HOST}:${config?.server?.port || BACKEND_PORT}`,
      provider: config?.llm?.provider || 'ollama',
      model: config?.llm?.ollama?.model || '',
      baseUrl: config?.llm?.ollama?.['base-url'] || '',
    };
  } catch {
    return {
      backendUrl: `http://${BACKEND_HOST}:${BACKEND_PORT}`,
      provider: 'ollama',
      model: 'qwen2.5:7b',
      baseUrl: 'http://localhost:11434',
    };
  }
});

app.whenReady().then(async () => {
  await startBackend();
  createWindow();

  if (!isDev) {
    autoUpdater.checkForUpdatesAndNotify().catch(() => {
      /* 无发布配置或离线时静默忽略 */
    });
  }
});

app.on('window-all-closed', () => {
  stopBackend();
  if (process.platform !== 'darwin') app.quit();
});

app.on('before-quit', () => {
  stopBackend();
});

app.on('activate', () => {
  if (mainWindow === null) createWindow();
});
