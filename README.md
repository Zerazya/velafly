# VelaFly

Temps de vol pour Paper 26.2, avec **bons en papier** : clic droit sur le papier pour ajouter son temps de vol (accroupi = tout le paquet).

## Commandes
- `/fly [on|off] [joueur]` : active ou coupe le vol (consomme le temps restant)
- `/flytime [joueur]` : temps restant
- `/velafly withdraw <durée> [nombre]` : transforme son temps en papiers (échangeables entre joueurs)
- Admin : `/velafly add|remove|set <joueur> <durée>`, `/velafly check <joueur>`, `/velafly voucher <durée> [nombre] [joueur]`, `/velafly reload`

Durées : `30m`, `1h30m`, `2d`, `45s`, `1w` (nombre seul = minutes).

## Permissions
`velafly.use`, `velafly.time`, `velafly.redeem`, `velafly.withdraw` (par défaut) · `velafly.unlimited`, `velafly.admin`, `velafly.fly.others`, `velafly.time.others` (op) · `velafly.bypass.pvp`

Si EssentialsX est présent, utilisez `/velafly:fly` (ou `/vfly`) pour éviter le conflit avec son `/fly`.

Build : `./gradlew build` (Java 25) ou GitHub Actions.
