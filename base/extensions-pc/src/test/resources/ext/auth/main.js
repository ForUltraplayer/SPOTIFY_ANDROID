// Opens a sign-in page named after the button pressed (the fake window reads what to do from its address) and keeps
// the opened id, the refusal, and every `auth` event in its storage, where the test reads them.
myspoti.on('setting', function (e) {
  if (!e.pressed) return;
  try {
    var page = e.key === 'self'
      ? { url: 'https://login.example.test/self?code=s3cret-code', callback: 'https://login.example.test/self', hidden: true }
      : { url: 'https://login.example.test/' + e.key, callback: 'testauth://done', hidden: e.key !== 'visible' };
    var id = myspoti.auth.open(page);
    myspoti.storage.set('opened', id);
  } catch (err) {
    myspoti.storage.set('refused', String(err.message || err));
  }
});
myspoti.on('auth', function (r) {
  var all = myspoti.storage.get('results') || [];
  all.push(r);
  myspoti.storage.set('results', all);
});
