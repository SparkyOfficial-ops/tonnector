# TONNECTOR

Локальный мост TonConnect 2.0 и mock-кошелёк. dApp на localhost подключается к кошельку на этой же машине.

## Запуск

```bash
gradle jar
java -jar build/libs/tonnector.jar --port=8080
```

Консоль без окна:

```bash
java -jar build/libs/tonnector.jar --cli --port=8080
```

В CLI на запрос: `a` подписать, `r` отклонить, `b` bad request, `n` method not supported.

При первом запуске в `tonnector.json` записывается мнемоника тестового кошелька. Это локальный ключ, не для реальных средств.

## Как подключить свой dApp

Сервер слушает `127.0.0.1` и отдаёт CORS для страницы на localhost.

```javascript
const [wallet] = await (await fetch("http://127.0.0.1:8080/wallets-v2.json")).json();
await connector.connect(wallet);
```

## Кошелёк

В `tonnector.json` задаются `version` (`v4r2` или `v5r1`), `network` (`testnet` или `mainnet`), мнемоника или `privateKey` и `seqno`.

Ответы с ошибками:

| Кнопка | code |
| --- | --- |
| Reject | 300 |
| Bad request | 1 |
| Not supported | 400 |