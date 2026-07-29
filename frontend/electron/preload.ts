import { contextBridge, ipcRenderer } from 'electron';

contextBridge.exposeInMainWorld('dbdocai', {
  platform: process.platform,
  versions: {
    node: process.versions.node,
    electron: process.versions.electron,
  },
  getConfig: () => ipcRenderer.invoke('get-config'),
});
