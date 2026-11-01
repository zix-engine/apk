'use strict';
const { boot, ask } = require('./_cli');
const { resetPassword } = require('../src/setup');

(async () => {
  const { config, db, repo } = boot();
  try {
    const username = process.env.TARGET_USERNAME || (await ask('Username yang direset: '));
    let password = process.env.NEW_PASSWORD;
    if (!password) {
      password = await ask('Password baru: ', true);
      const again = await ask('Ulangi password baru: ', true);
      if (password !== again) throw new Error('Password tidak sama.');
    }
    await resetPassword(repo, config, username, password);
    console.log('Password diganti dan semua sesi akun itu dicabut.');
  } finally {
    await db.close();
  }
})().catch((e) => {
  console.error('Gagal:', e.message);
  process.exit(1);
});
