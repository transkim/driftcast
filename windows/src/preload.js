'use strict';
const { contextBridge, ipcRenderer } = require('electron');

const on = (ch, fn) => ipcRenderer.on(ch, (_e, payload) => fn(payload));

contextBridge.exposeInMainWorld('driftcast', {
  info: () => ipcRenderer.invoke('info'),
  onInfo: (fn) => on('info', fn),
  onStatus: (fn) => on('status', fn),
  onAsk: (fn) => on('ask', fn),
  onAskCancel: (fn) => on('ask-cancel', fn),
  onStart: (fn) => on('start', fn),
  onEnded: (fn) => on('ended', fn),
  onConfig: (fn) => on('config', fn),
  onFrame: (fn) => on('frame', fn),
  onPcm: (fn) => on('pcm', fn),
  onFullscreen: (fn) => on('fullscreen', fn),
  answer: (reqId, answer) => ipcRenderer.send('answer', { reqId, answer }),
  requestKey: () => ipcRenderer.send('request-key'),
  stop: () => ipcRenderer.send('stop'),
  rename: (name) => ipcRenderer.send('rename', name),
  forget: () => ipcRenderer.send('forget'),
  fullscreen: (on) => ipcRenderer.send('fullscreen', on),
  help: () => ipcRenderer.send('open-help'),
});
