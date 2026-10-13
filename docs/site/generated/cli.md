IdleO's command line, `idleo`: rent a GPU by the minute, from your terminal.

## Options for every command

| Argument      | What it does                                        |
| ------------- | --------------------------------------------------- |
| `--api <URL>` | The API's address.                                  |
| `--json`      | Print JSON and nothing else on stdout, for scripts. |

## `idleo login`

Sign in through the web app.

```
idleo login [OPTIONS]
```

| Argument               | What it does                                                                      |
| ---------------------- | --------------------------------------------------------------------------------- |
| `--device-name <NAME>` | What to call this computer in the web app's approval page and in your key's name. |
| `--no-browser`         | Print the link, but don't open it.                                                |

## `idleo logout`

Sign out, and revoke this computer's key.

```
idleo logout
```

## `idleo whoami`

Show who's signed in.

```
idleo whoami
```

## `idleo credit`

Your credit: available, on hold, and the history.

```
idleo credit [COMMAND]
```

### `idleo credit history`

Every movement of your credit, newest first.

```
idleo credit history [OPTIONS]
```

| Argument          | What it does                   |
| ----------------- | ------------------------------ |
| `--limit <LIMIT>` | How many entries. Default: 20. |

## `idleo templates`

What you can run, and what each takes.

```
idleo templates [COMMAND]
```

### `idleo templates show`

One template: its inputs, questions and parameters.

```
idleo templates show <ID>
```

| Argument | What it does |
| -------- | ------------ |
| `<ID>`   | .            |

## `idleo gpus`

What GPUs are free now, by memory.

```
idleo gpus
```

## `idleo run`

Book a GPU for a template, then follow it until it ends.

```
idleo run <TEMPLATE> [OPTIONS]
```

| Argument                       | What it does                                                                                                  |
| ------------------------------ | ------------------------------------------------------------------------------------------------------------- |
| `<TEMPLATE>`                   | The template to run (see `templates`).                                                                        |
| `-i, --input <NAME=PATH\|URL>` | An input, as `name=PATH` or `name=https://…`; repeat it for each input. With one input, `--input PATH` alone. |
| `--param <NAME=VALUE>`         | A parameter, as `name=value`.                                                                                 |
| `--preset <QUESTION=OPTION>`   | A question's answer, as `question=option`.                                                                    |
| `--max-spend <RUPEES>`         | The most to spend on this session, in rupees; the template's usual budget if left out.                        |
| `--max-price <RUPEES>`         | The highest price you'll pay, in rupees an hour; the best GPU that fits is used if left out.                  |
| `--gpu-memory <SIZE>`          | GPU memory, like 12GB, when the template asks.                                                                |
| `--max-runtime <DURATION>`     | The longest it may run, like 90m or 2h.                                                                       |
| `--detach`                     | Book and print the session's id, without following it.                                                        |

## `idleo ls`

Your sessions.

```
idleo ls [OPTIONS]
```

| Argument          | What it does                               |
| ----------------- | ------------------------------------------ |
| `--all`           | Include sessions that have ended.          |
| `--state <STATE>` | Only sessions in this state, like running. |
| `--limit <LIMIT>` | How many sessions. Default: 20.            |

## `idleo status`

One session: its state, cost so far, and GPU.

```
idleo status <ID>
```

| Argument | What it does      |
| -------- | ----------------- |
| `<ID>`   | The session's id. |

## `idleo logs`

A session's logs.

```
idleo logs <ID> [OPTIONS]
```

| Argument       | What it does                                |
| -------------- | ------------------------------------------- |
| `<ID>`         | .                                           |
| `-f, --follow` | Keep following until the session has ended. |

## `idleo watch`

Follow a session's state as it changes.

```
idleo watch <ID>
```

| Argument | What it does      |
| -------- | ----------------- |
| `<ID>`   | The session's id. |

## `idleo stop`

Stop a running session.

```
idleo stop <ID> [OPTIONS]
```

| Argument          | What it does                                    |
| ----------------- | ----------------------------------------------- |
| `<ID>`            | .                                               |
| `--no-checkpoint` | Stop at once, without saving a last checkpoint. |

## `idleo cancel`

Cancel a session that hasn't started.

```
idleo cancel <ID>
```

| Argument | What it does      |
| -------- | ----------------- |
| `<ID>`   | The session's id. |

## `idleo outputs`

A session's outputs, and downloading them.

```
idleo outputs <ID> [OPTIONS]
```

| Argument           | What it does                                                                |
| ------------------ | --------------------------------------------------------------------------- |
| `<ID>`             | .                                                                           |
| `--download <DIR>` | Download the outputs into this folder; a partly downloaded file carries on. |

## `idleo keys`

Your API keys.

```
idleo keys [COMMAND]
```

### `idleo keys create`

Make a new full key, approved in the web app like a sign-in.

```
idleo keys create [OPTIONS]
```

| Argument        | What it does                             |
| --------------- | ---------------------------------------- |
| `--name <NAME>` | What to call it; shown as "CLI on NAME". |

### `idleo keys revoke`

Revoke a key by its id.

```
idleo keys revoke <ID>
```

| Argument | What it does |
| -------- | ------------ |
| `<ID>`   | .            |

## Exit codes

| Code | Meaning                                                              |
| ---- | -------------------------------------------------------------------- |
| 0    | Success, or the session completed                                    |
| 1    | The session failed, or the API refused something no other code names |
| 2    | Wrong usage                                                          |
| 3    | Not enough credit                                                    |
| 4    | No host free                                                         |
| 5    | Not signed in                                                        |
| 6    | Rate limited                                                         |
| 7    | Network                                                              |
