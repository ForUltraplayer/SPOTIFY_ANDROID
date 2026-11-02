myspoti.provide('audio-source', function (track) {
  return {
    url: 'https://media.example.com/' + track.id + '.bin', codec: 'flac',
    transform: { algorithm: 'blowfish-cbc-pattern', keyHex: '00112233445566778899aabbccddeeff', ivHex: '0001020304050607' }
  };
});
