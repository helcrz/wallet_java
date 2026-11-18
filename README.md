# Atelier portefeuille

Simulateur de portefeuille pour actions et ETF en USD. Java 21, JavaFX 21 et Maven. L’application conserve les liquidités, les positions, le profil de risque et le journal des achats et ventes entre deux lancements.

## Lancer sur ce poste

Dans PowerShell, depuis le dossier du projet :

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\maven.ps1 javafx:run
```

Le script utilise le JDK et Maven de `.tools`. Sur un autre poste équipé d’un JDK 21 et de Maven 3.9, lancer `mvn javafx:run`. Maven télécharge les dépendances lors du premier lancement. Le fichier JAR produit par `package` contient le code et les ressources du projet. Le lanceur Maven fournit JavaFX et les bibliothèques à l’exécution.

### Choisir une action ou un ETF

Dans **Marché et ordres**, cliquer sur **Choisir un titre** pour parcourir la liste ou la filtrer par nom ou symbole. Sélectionner une ligne puis cliquer sur **Sélectionner** pour consulter son cours. Le titre choisi est repris dans l’aperçu, l’achat et la vente. La saisie directe par symbole reste disponible.

En mode Alpaca, le catalogue comprend les titres américains actifs et négociables hors OTC. La disponibilité d’une transaction récente dépend du flux IEX. Alpha Vantage fournit sa liste de cotations américaines actives, et le mode démonstration présente les cinq titres simulés. Le catalogue est chargé à la demande et conservé en mémoire pour la journée.

### Démonstration

Sans clé, l’application utilise cinq historiques fictifs reproductibles de 100 séances. Au premier lancement, choisir un nom et un capital initial, par exemple 10 000 USD.

1. Dans **Marché et ordres**, consulter IBM, saisir 5 et cliquer sur **Étudier l’achat**. Comparer le poids du titre et la volatilité avant et après.
2. Cliquer sur **Acheter**, puis acheter quelques parts de SPY. Le tableau du portefeuille présente le prix moyen, la date de chaque clôture et le gain latent. Le journal conserve les deux opérations.
3. Dans **Analyse**, choisir et appliquer un profil, puis calculer les indicateurs. Observer la corrélation, les seuils et la courbe de valeur de la composition actuelle.
4. Dans **Markowitz**, saisir `IBM, SPY, TLT`, fixer un plafond de 60 % et laisser la cible vide. Comparer les poids proposés aux poids du portefeuille. Une cible trop élevée produit un message d’infaisabilité.
5. Vendre une fraction d’une position, fermer puis relancer. Les quantités, les liquidités et le journal sont rechargés.

### Cours pendant la séance avec Alpaca

Un compte **Alpaca Paper** avec l’offre **Basic** donne accès aux transactions en temps réel de la place américaine IEX pour les actions et ETF américains. Créer une paire de clés dans le tableau de bord Alpaca, puis lancer l’application dans le même terminal PowerShell. [Accès et authentification](https://docs.alpaca.markets/us/docs/about-market-data-api)

```powershell
$env:APCA_API_KEY_ID = Read-Host 'Identifiant API Alpaca'
$env:APCA_API_SECRET_KEY = [System.Net.NetworkCredential]::new('', (Read-Host 'Secret Alpaca' -AsSecureString)).Password
powershell -NoProfile -ExecutionPolicy Bypass -File .\maven.ps1 javafx:run
```

Dans **Marché et ordres**, saisir par exemple `AAPL` et cliquer sur **Consulter le cours**. Pendant que cet onglet affiche le titre, le suivi interroge IEX toutes les 15 secondes. L’heure affichée est celle de la transaction, convertie à l’heure de Paris. En dehors de la séance ou pour un titre peu échangé, le dernier échange peut être ancien. Un ordre simulé demande son propre prix au fournisseur et enregistre l’horodatage exact. Le bouton **Actualiser les cours** met à jour les positions détenues. [Dernière transaction IEX](https://docs.alpaca.markets/us/reference/stocklatesttradesingle-1)

Les graphiques et le calcul de risque utilisent jusqu’à 100 clôtures de séances antérieures, chargées une fois par jour et par titre. Les deux clés Alpaca suffisent pour les cours et l’historique. Quand elles sont présentes, l’application choisit Alpaca en priorité. Les symboles hors du petit catalogue de métadonnées sont regroupés sous « Titre US » dans la répartition par type. [Historique des cours](https://docs.alpaca.markets/us/reference/stockbarsingle-1)

### Clôtures quotidiennes avec Alpha Vantage

Définir la clé dans la session PowerShell qui lance l’application :

```powershell
$env:ALPHAVANTAGE_API_KEY = 'votre-cle'
powershell -NoProfile -ExecutionPolicy Bypass -File .\maven.ps1 javafx:run
```

Le client cherche une correspondance exacte avec `SYMBOL_SEARCH`, vérifie la devise et le type, puis charge `TIME_SERIES_DAILY` en format compact. La dernière clôture datée sert de prix aux transactions simulées. Une première consultation utilise deux requêtes. L’historique est conservé en mémoire pour la journée et les boutons d’actualisation permettent de le renouveler. Les erreurs réseau, le quota ou un prix invalide interrompent l’opération. Les clôtures brutes peuvent présenter des sauts liés aux divisions d’actions et excluent les dividendes. [Documentation Alpha Vantage](https://www.alphavantage.co/documentation/#daily)

Les données sont enregistrées dans `~/.wallet-java/demo.json` ou `~/.wallet-java/portefeuille.json` selon le mode. Pour revenir en démonstration, ouvrir un nouveau terminal sans la variable de clé. Chaque modification est écrite dans un fichier temporaire puis publiée par remplacement atomique. Le verrou empêche deux instances d’écrire simultanément dans le même portefeuille. Une sauvegarde illisible est signalée à l’ouverture.

## Architecture retenue

Le document de conception `Projet_java (1).pdf`, section 2, guide l’organisation. Les structures de résultats et le profil sont des types imbriqués dans les sept classes principales.

| Classe | Responsabilité |
|---|---|
| MainApp | Création ou rechargement du portefeuille, ouverture de la fenêtre |
| DashboardController | Saisie, graphiques, tableaux et exécution des opérations sur un thread de travail |
| AssetPosition | Quantité, coût moyen pondéré, valorisation et gain latent |
| Portfolio | État immuable du portefeuille, contrôles des transactions et journal |
| PortfolioService | Coordination, scénarios, sauvegarde JSON et verrouillage |
| MarketDataApi | Métadonnées, validation des cours, cache et séries de démonstration |
| AnalysisService | Calcul des indicateurs, recommandations et optimisation quadratique |

Les quantités et montants utilisent `BigDecimal`. Un achat recalcule le coût moyen pondéré. Une vente conserve le prix moyen des titres restants. Liquider une position la retire du portefeuille et conserve sa trace au journal. Les frais, la fiscalité et les intérêts sur les liquidités sont supposés nuls.

## Lecture des résultats

- **Performance depuis la création** = (valeur courante − capital initial) / capital initial. La valeur courante inclut les liquidités. Le gain total comprend donc les gains réalisés et latents.
- **Risque**. Pour chaque titre, le rendement simple est le rapport entre deux clôtures successives moins un. Deux titres sont comparés uniquement sur des intervalles ayant les mêmes dates de début et de fin, consécutives dans chaque série. Deux observations communes au minimum sont nécessaires. La covariance empirique est divisée par `T − 1`.
- **Annualisation**. Pour des poids actuels `w`, le rendement moyen annualisé vaut `252 × wᵀμ` et la volatilité `√(252 × wᵀΣw)`. Les liquidités portent un rendement et une variance nuls. Le Sharpe utilise un taux sans risque de 0 %. Un écart-type nul rend la corrélation ou le Sharpe concerné indéfini.
- **Courbe de valeur**. Chaque point revalorise les quantités et liquidités actuelles aux cours de la date. C’est un scénario historique à composition fixe. Le journal retrace les opérations effectivement simulées.
- **Profils**. Les seuils indicatifs de volatilité annuelle sont de 10 %, 20 % et 30 % pour les profils prudent, équilibré et dynamique. Le risque est faible jusqu’à la moitié du seuil, modéré jusqu’au seuil et élevé au-delà. Les repères de concentration sont de 25 %, 40 % et 60 %. Une corrélation supérieure à 0,80 déclenche une remarque sur la diversification.
- **Markowitz**. ojAlgo minimise `wᵀΣw` sous les contraintes `Σwᵢ = 1`, `0 ≤ wᵢ ≤ plafond` et, si renseigné, `252 × wᵀμ ≥ cible`. L’allocation théorique investit tout le capital dans les titres choisis. Les poids actuels affichés utilisent le capital total, liquidités comprises. Le calcul laisse à l’utilisateur la décision d’effectuer une simulation d’ordre.

## Compiler et tester

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\maven.ps1 test
powershell -NoProfile -ExecutionPolicy Bypass -File .\maven.ps1 '-Dwallet.ui=true' package
```

La première commande exécute les tests métier et le serveur HTTP local simulant Alpha Vantage. La seconde inclut le parcours JavaFX et requiert une session graphique. Les rapports JUnit sont produits dans `target/surefire-reports`. Les tests utilisent des portefeuilles temporaires.
