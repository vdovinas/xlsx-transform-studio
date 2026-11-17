'use strict';
if (!window.studio) {
  const { invoke } = window.__TAURI__.core;
  const { listen } = window.__TAURI__.event;
  const call = operation => (request = null) => invoke('studio', { operation, request });
  const on = event => callback => {
    const subscription = listen(event, message => callback(message.payload));
    return () => subscription.then(unlisten => unlisten());
  };
  window.studio = {
    bootstrap: call('bootstrap'), chooseInput: call('choose-input'), inspect: call('inspect'),
    convert: call('convert'), cancel: call('cancel'), revealOutput: call('reveal-output'),
    saveProfiles: call('save-profiles'), confirmProfileDeletion: call('confirm-profile-deletion'),
    onProgress: on('progress'), onClosing: on('closing'),
  };
}
