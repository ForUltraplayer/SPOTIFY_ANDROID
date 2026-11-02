// An audio source without the song: it answers nothing, the next source is asked.
myspoti.provide('audio-source', function (track) { return null; });
