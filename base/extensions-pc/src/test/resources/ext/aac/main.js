myspoti.provide('audio-source', function (track) {
  return { url: 'https://media.example.com/' + track.id + '.m4a', mimeType: 'audio/mp4', codec: 'aac' };
});
