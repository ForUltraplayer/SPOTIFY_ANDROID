// Answers a made-up FLAC address for every song; the address carries how many times it was asked.
myspoti.provide('audio-source', function (track) {
  var n = (myspoti.storage.get('asked') || 0) + 1;
  myspoti.storage.set('asked', n);
  return {
    url: 'https://media.example.com/' + track.id + '.flac?n=' + n + '&ahead=' + track.ahead,
    headers: { 'X-Test': 'yes' },
    mimeType: 'audio/flac', codec: 'flac', bitDepth: 24, sampleRate: 96000, expiresInMs: 600000
  };
});
