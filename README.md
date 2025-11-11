# TONNECTOR

Локальный мост TonConnect 2.0 и mock-кошелёк. dApp на `localhost` подключается к кошельку на этой же машине: без телефона, ngrok и публичного манифеста.

## Запуск

```bash
gradle jar
java -jar build/libs/tonnector.jar --port=8080
```

Или сразу:

```bash
gradle run --args="--port=8080"
```

Консоль без окна:

```bash
java -jar build/libs/tonnector.jar --cli --port=8080
```

В CLI на запрос: `a` подписать, `r` отклонить, `b` bad request, `n` method not supported.

При первом запуске в `tonnector.json` записывается мнемоника тестового кошелька. Это локальный ключ, не для реальных средств. Файл лучше не коммитить.

Полезные адреса:

- консоль запросов: `http://127.0.0.1:8080`
- пример dApp: `http://127.0.0.1:8080/sample/`
- список кошельков: `http://127.0.0.1:8080/wallets-v2.json`
- мост: `http://127.0.0.1:8080/bridge`

## Как подключить свой dApp

Сервер слушает только `127.0.0.1` и отдаёт CORS `*` вместе с `Access-Control-Allow-Private-Network`, поэтому страница на `http://localhost:3000` может ходить на мост напрямую.

```javascript
const connector = new TonConnect({
  manifestUrl: "http://localhost:3000/tonconnect-manifest.json",
});

const [wallet] = await (await fetch("http://127.0.0.1:8080/wallets-v2.json")).json();
await connector.connect(wallet);
```

Для `@tonconnect/ui`:

```javascript
new TonConnectUI({
  manifestUrl: "http://localhost:3000/tonconnect-manifest.json",
  walletsListConfiguration: {
    walletsListSource: "http://127.0.0.1:8080/wallets-v2.json",
  },
});
```

Клик по TONNECTOR открывает `http://127.0.0.1:8080/ton-connect?...`. На этой странице, в окне приложения или в консоли на `/` нажмите **Approve**. Дальше `sendTransaction` и `signData` приходят в тот же список.

Ссылка, которую показывает dApp, также принимается в поле «Paste a tc:// or http connect link». Рядом строится QR через ZXing.

## Кошелёк

`tonnector.json`:

- `version`: `v4r2` или `v5r1`
- `network`: `testnet` (`-3`) или `mainnet` (`-239`)
- `mnemonic` или `privateKey` (64 hex-символа, 32 байта seed)
- `seqno`: номер исходящего сообщения; при `0` в BOC кладётся state init

Адрес и публичный ключ выводятся из этого ключа и версии. Подпись `ton_proof` и тело транзакции собираются этим же ключом, поэтому ответ можно отправить в сеть, если на адресе есть баланс и `seqno` совпадает с контрактом.

Ответы с ошибками, которые уходят в dApp:

| Кнопка | code | смысл |
| --- | --- | --- |
| Reject | 300 | USER_DECLINED |
| Bad request | 1 | BAD_REQUEST |
| Not supported | 400 | METHOD_NOT_SUPPORTED |

## Что внутри

Kotlin/JVM, Ktor (HTTP и SSE), криптография ton-kotlin 0.4.3 (ed25519, Cell/BOC, Wallet V4R2) и TweetNaCl (X25519 `nacl.box`, как в спецификации сессии TonConnect). Код кошелька V5R1 вшит как стандартный BOC. Наружу процесс не ходит, кроме чтения манифеста dApp по адресу из connect-запроса.
