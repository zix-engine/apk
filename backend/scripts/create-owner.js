'use strict';
const { boot, ask } = require('./_cli');
const { createFirstOwner } = require('../src/setup');

(async () => {
  const { config, db, repo } = boot();
  try {
    const username = process.env.OWNER_USERNAME || (await ask('Username Owner: '));
    const displayName = process.env.OWNER_NAME || (await ask('Nama tampilan (Enter = sama dengan username): '));
    let password = process.env.OWNER_PASSWORD;
    if (!password) {
      password = await ask('Password (min 10 karakter, huruf+angka): ', true);
      const again = await ask('Ulangi password: ', true);
      if (password !== again) throw new Error('Password tidak sama.');
    }
    await createFirstOwner(repo, config, { username, displayName, password });
    console.log('Owner "' + username.trim().toLowerCase() + '" berhasil dibuat. Sekarang login dari aplikasi.');
  } finally {
    await db.close();
  }
})().catch((e) => {
  console.error('Gagal:', e.message);
  process.exit(1);
});
