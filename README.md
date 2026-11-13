<p align="center">
  <img src="assets/banner.svg" width="100%" alt="entinyGram" />
</p>

# entinyGram

an independent StGit patchset on top of official Telegram and Inugram.

## highlights

everything entinygram adds lives in **settings → entinygram**, grouped into pages. the full list is in [FEATURES.md](FEATURES.md).

- [ghost mode](FEATURES.md#privacy--protection): hide read receipts, story views, online status and typing.
- [kept deleted messages](FEATURES.md#privacy--protection): keep deleted messages and browse the edit history.
- [protected content](FEATURES.md#unlocks--restrictions): copy, save and forward from protected chats.
- [translation & ai](FEATURES.md#translation--ai): translate chats, transcribe voice, use your own ai providers.
- [forward pro](FEATURES.md#messages--sending): forwards with grouped albums, comments and a live preview.
- [custom interface](FEATURES.md#appearance--customization): themes, tabs, chat layout and message menus.
- [plugin system](FEATURES.md#proxy-plugins--performance): plugins from the catalog or your own with the sdk.

## docs

| guide | contents |
| --- | --- |
| [all features](FEATURES.md) | every entinygram feature |
| [removed features](docs/removed.md) | what was removed and why |
| [build guide](https://entinygram.vercel.app/build) | prerequisites, setup and signing |
| [plugin sdk](sdk/docs/README.md) | plugin api and authoring |
| [docs index](docs/README.md) | contributor guides and skills |

## quick start

signed apks are on the [releases](https://github.com/entaytion/entinyGram/releases) page.

```sh
git clone https://github.com/entaytion/entinyGram.git
cd entinyGram
bun install
bun run setup
```

## credits

entinygram builds on [inugram](https://github.com/teidesu/inugram) by [@teidesu](https://github.com/teidesu) and the official [telegram for android](https://github.com/DrKLO/Telegram) client. thanks to the forks credited next to each feature in [FEATURES.md](FEATURES.md).

## license

the patchset and fork code are released under the [MIT license](LICENSE). the built app contains telegram for android (GPL-2.0-or-later) and other third-party components under their own licenses.
