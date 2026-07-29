import { app, BrowserWindow, ipcMain } from 'electron';
import * as path from 'path';
import * as fs from 'fs';
import * as yaml from 'yaml';

let mainWindow: BrowserWindow | null = null;
const isDev = process.env.NODE_ENV === 'development' || !app.isPackaged;

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

ipcMain.handle('get-config', async () => {
  const configPath = path.join(app.getAppPath(), '..', 'backend', 'src', 'main', 'resources', 'application.yml');
  try {
    const content = fs.readFileSync(configPath, 'utf-8');
    const config = yaml.parse(content);
    return {
      backendUrl: `http://${config?.server?.address || '127.0.0.1'}:${config?.server?.port || 8080}`,
      provider: config?.llm?.provider || 'ollama',
      model: config?.llm?.ollama?.model || '',
      baseUrl: config?.llm?.ollama?.['base-url'] || '',
    };
  } catch {
    return {
      backendUrl: 'http://127.0.0.1:8080',
      provider: 'ollama',
      model: 'qwen2.5:7b',
      baseUrl: 'http://localhost:11434',
    };
  }
});

app.whenReady().then(createWindow);

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

app.on('activate', () => {
  if (mainWindow === null) createWindow();
});
