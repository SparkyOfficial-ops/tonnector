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

## Как подключить свой dApp

```javascript
const [wallet] = await (await fetch("http://127.0.0.1:8080/wallets-v2.json")).json();
await connector.connect(wallet);
```

Клик по TONNECTOR открывает страницу подтверждения. Approve возвращает адрес и ton_proof.