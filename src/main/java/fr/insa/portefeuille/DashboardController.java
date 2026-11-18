package fr.insa.portefeuille;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public final class DashboardController implements AutoCloseable {
    private final PortfolioService service;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final BorderPane root = new BorderPane();
    private final TabPane tabs = new TabPane();
    private final Label status = new Label(), summary = new Label(), quote = new Label();
    private final TableView<AssetPosition> positions = new TableView<>();
    private final TableView<Portfolio.Trade> journal = new TableView<>();
    private final TextField symbol = new TextField("IBM"), quantity = new TextField("1");
    private final TextField selection = new TextField(), cap = new TextField("100"), target = new TextField();
    private final ComboBox<Portfolio.Profile> profile = new ComboBox<>();
    private final TextArea analysis = output(), proposal = output(), scenario = output();
    private final PieChart distribution = new PieChart();
    private final NumberAxis dates = new NumberAxis(), prices = new NumberAxis();
    private final LineChart<Number,Number> chart = new LineChart<>(dates, prices);
    private Map<String,MarketDataApi.Series> market = Map.of();
    private static final DateTimeFormatter QUOTE_TIME = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss z", Locale.FRANCE)
            .withZone(ZoneId.of("Europe/Paris"));
    private MarketDataApi.Series watched;
    private boolean polling;
    private final Timeline timer = new Timeline(new KeyFrame(javafx.util.Duration.seconds(15), e -> pollQuote()));

    private final LineChart<Number,Number> valuationChart = new LineChart<>(new NumberAxis(), new NumberAxis());

    public DashboardController(PortfolioService service) {
        this.service = service;
        var title = new Label(service.portfolio().name());
        title.getStyleClass().add("heading");
        var banner = new VBox(title, new Label(service.demo() ? "Démonstration · cours fictifs en USD" :
                service.live() ? "Alpaca · transactions IEX en USD · suivi du titre toutes les 15 s" : "Alpha Vantage · clôtures quotidiennes en USD"));
        banner.getStyleClass().add("banner");
        root.setTop(banner);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(new Tab("Portefeuille", holdings()), new Tab("Marché et ordres", orders()),
                new Tab("Analyse", analytics()), new Tab("Markowitz", optimization()), new Tab("Journal", history()));
        root.setCenter(tabs);
        status.getStyleClass().add("status");
        status.setMaxWidth(Double.MAX_VALUE);
        status.setWrapText(true);
        root.setBottom(status);
        displayBook();
        if (service.live()) { timer.setCycleCount(Timeline.INDEFINITE); timer.play(); }
    }

    public BorderPane root() { return root; }
    public boolean busy() { return tabs.isDisabled(); }
    public void close() { timer.stop(); watched = null; executor.shutdownNow(); }

    private static TextArea output() {
        var area = new TextArea();
        area.setEditable(false); area.setWrapText(true);
        return area;
    }
    private static VBox page(Node... nodes) {
        var box = new VBox(nodes);
        box.getStyleClass().add("page");
        return box;
    }
    private static HBox row(Node... nodes) {
        var box = new HBox(9, nodes);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }
    private Button button(String label, Runnable action) {
        var button = new Button(label);
        button.setOnAction(e -> {
            try { action.run(); } catch (RuntimeException ex) { message(ex.getMessage(), true); }
        });
        return button;
    }
    private static <T> void column(TableView<T> table, String title, Function<T,String> content) {
        var column = new TableColumn<T,String>(title);
        column.setCellValueFactory(c -> new ReadOnlyStringWrapper(content.apply(c.getValue())));
        column.setSortable(false);
        table.getColumns().add(column);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }
    private static String money(BigDecimal v) { return String.format(Locale.FRANCE,"%,.2f",v); }
    private static String percent(double d) { return String.format(Locale.FRANCE,"%.2f %%",d*100); }
    private static String number(double d) { return Double.isFinite(d) ? String.format(Locale.FRANCE,"%.3f",d) : "indéfini"; }
    private static BigDecimal decimal(TextField field, String label) {
        String input = field.getText().trim().replace(',', '.');
        if (!input.matches("[+-]?[0-9]{1,12}([.][0-9]{1,8})?"))
            throw new IllegalArgumentException(label + " : saisis un nombre décimal (8 décimales maximum).");
        return new BigDecimal(input);
    }

    private Node holdings() {
        summary.setWrapText(true);
        summary.getStyleClass().add("metric");
        column(positions,"Symbole", AssetPosition::symbol);
        column(positions,"Type", AssetPosition::type);
        column(positions,"Quantité", a -> a.quantity().stripTrailingZeros().toPlainString());
        column(positions,"Prix moyen", a -> money(a.averageBuyPrice()));
        column(positions,"Dernier prix", a -> market.containsKey(a.symbol()) ? money(market.get(a.symbol()).price()) : "—");
        column(positions,"Date du prix", a -> market.containsKey(a.symbol()) ? stamp(market.get(a.symbol()).priceStamp()) : "—");
        column(positions,"Valeur", a -> market.containsKey(a.symbol()) ? money(a.value(market.get(a.symbol()).price())) : "—");
        column(positions,"G/P latent", a -> market.containsKey(a.symbol()) ? money(a.profit(market.get(a.symbol()).price())) : "—");
        positions.setPlaceholder(new Label("Aucune position. Un premier achat peut être simulé dans « Marché et ordres »."));
        positions.getSelectionModel().selectedItemProperty().addListener((obs, old, a) -> {
            if (a != null) symbol.setText(a.symbol());
        });
        distribution.setTitle("Répartition par type, liquidités comprises");
        distribution.setAnimated(false);
        distribution.setPrefHeight(215);
        VBox.setVgrow(positions, Priority.ALWAYS);
        return page(summary, button("Actualiser les cours", () -> refresh(true)), positions, distribution);
    }
    private Node history() {
        column(journal,"Date opération", Portfolio.Trade::date);
        column(journal,"Date du prix", t -> stamp(t.priceDate()));
        column(journal,"Opération", t -> t.purchase() ? "Achat" : "Vente");
        column(journal,"Symbole", Portfolio.Trade::symbol);
        column(journal,"Quantité", t -> t.quantity().toPlainString());
        column(journal,"Cours en USD", t -> money(t.price()));
        column(journal,"Montant en USD", t -> money(t.quantity().multiply(t.price())));
        journal.setPlaceholder(new Label("Les opérations enregistrées apparaîtront ici."));
        VBox.setVgrow(journal, Priority.ALWAYS);
        return page(journal);
    }
    private Node orders() {
        symbol.setPrefColumnCount(8); quantity.setPrefColumnCount(6);
        var browse = button("Choisir un titre", () -> run("Chargement du catalogue…", service::catalog, this::showCatalog));
        var search = button("Consulter le cours", this::lookupQuote);
        var buy = button("Acheter", () -> trade(true));
        buy.getStyleClass().add("primary");
        quote.setWrapText(true);
        quote.setText("Choisis un titre dans le catalogue ou saisis son symbole.");
        configureDates(dates);
        prices.setForceZeroInRange(false);
        prices.setLabel("Clôture (USD)");
        chart.setCreateSymbols(false); chart.setLegendVisible(false); chart.setAnimated(false);
        chart.setMinHeight(140); chart.setPrefHeight(285);
        scenario.setPrefRowCount(4);
        scenario.setPromptText("L’aperçu affiche les liquidités, le poids du titre et le risque après l’achat.");
        symbol.textProperty().addListener((o,a,b) -> { watched = null; chart.getData().clear(); chart.setTitle(""); quote.setText(""); scenario.clear(); });
        quantity.textProperty().addListener((o,a,b) -> scenario.clear());
        VBox.setVgrow(chart, Priority.ALWAYS);
        var refreshQuote = button("Actualiser ce titre", () -> {
            String ticker = symbol.getText();
            run("Actualisation du titre…", () -> service.refreshQuote(ticker), data -> {
                invalidateResults(); displayQuote(data); refresh(false);
            });
        });
        return page(row(browse,new Label("Symbole"),symbol,search,refreshQuote),quote,chart,
                row(new Label("Quantité"),quantity,button("Étudier l’achat", this::preview),buy,button("Vendre", () -> trade(false))),scenario);
    }
    private void lookupQuote() {
        String ticker = symbol.getText();
        run("Recherche du cours…", () -> service.quote(ticker), this::displayQuote);
    }
    private void showCatalog(List<MarketDataApi.Instrument> instruments) {
        var dialog = new Dialog<MarketDataApi.Instrument>();
        dialog.initOwner(root.getScene().getWindow());
        dialog.setTitle("Choisir un titre");
        dialog.setResizable(true);
        var filter = new TextField();
        filter.setPromptText("Rechercher par nom ou symbole");
        var matches = new FilteredList<>(FXCollections.observableArrayList(instruments));
        var table = new TableView<>(matches);
        column(table,"Nom", MarketDataApi.Instrument::name);
        column(table,"Symbole", MarketDataApi.Instrument::symbol);
        column(table,"Place", MarketDataApi.Instrument::exchange);
        table.getColumns().get(0).setPrefWidth(370);
        table.setPlaceholder(new Label("Aucun titre correspondant."));
        filter.textProperty().addListener((o,old,value) -> {
            String term = value.strip().toLowerCase(Locale.ROOT);
            matches.setPredicate(i -> i.symbol().toLowerCase(Locale.ROOT).contains(term)
                    || i.name().toLowerCase(Locale.ROOT).contains(term));
            if (matches.size() == 1) table.getSelectionModel().selectFirst();
        });
        var pane = dialog.getDialogPane();
        var choose = new ButtonType("Sélectionner",ButtonBar.ButtonData.OK_DONE);
        pane.getButtonTypes().addAll(choose,ButtonType.CANCEL);
        var confirm = (Button)pane.lookupButton(choose);
        confirm.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        table.setRowFactory(t -> {
            var tableRow = new TableRow<MarketDataApi.Instrument>();
            tableRow.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !tableRow.isEmpty()) confirm.fire();
            });
            return tableRow;
        });
        var content = new VBox(10,filter,table);
        VBox.setVgrow(table,Priority.ALWAYS);
        pane.setContent(content);
        pane.setPrefSize(660,460);
        dialog.setResultConverter(b -> b == choose ? table.getSelectionModel().getSelectedItem() : null);
        dialog.setOnShown(e -> filter.requestFocus());
        dialog.setOnHidden(e -> {
            var chosen = dialog.getResult();
            if (chosen != null) {
                symbol.setText(chosen.symbol());
                lookupQuote();
            }
        });
        dialog.show();
    }
    private static void configureDates(NumberAxis dates) {
        dates.setForceZeroInRange(false);
        dates.setTickLabelFormatter(new StringConverter<>() {
            public String toString(Number n) { return LocalDate.ofEpochDay(n.longValue()).toString(); }
            public Number fromString(String s) { return LocalDate.parse(s).toEpochDay(); }
        });
    }
    private static String stamp(String value) {
        return value.contains("T") ? QUOTE_TIME.format(Instant.parse(value)) : value;
    }
    private void updatePrice(MarketDataApi.Series data) {
        watched = data;
        String source = service.live() ? "dernière transaction IEX" : service.demo() ? "clôture fictive" : "clôture Alpha Vantage";
        quote.setText(data.name() + " · " + data.symbol() + " · " + money(data.price()) + " USD · " + source + " · " + stamp(data.priceStamp()));
    }
    private void pollQuote() {
        var previous = watched;
        if (previous == null || polling || busy() || tabs.getSelectionModel().getSelectedIndex() != 1) return;
        polling = true;
        Task<MarketDataApi.Series> task = new Task<>() {
            @Override protected MarketDataApi.Series call() throws Exception { return service.quote(previous.symbol()); }
        };
        task.setOnSucceeded(e -> {
            polling = false;
            if (watched == previous && !busy()) {
                if (previous.prices().equals(task.getValue().prices())) updatePrice(task.getValue());
                else displayQuote(task.getValue());
            }
        });
        task.setOnFailed(e -> {
            polling = false;
            if (watched == previous && !busy()) {
                watched = null;
                message("Suivi interrompu. " + task.getException().getMessage() + " Clique sur « Actualiser ce titre » pour reprendre.", true);
            }
        });
        executor.execute(task);
    }
    private void displayQuote(MarketDataApi.Series data) {
        updatePrice(data);
        var line = new XYChart.Series<Number,Number>();
        data.prices().forEach((d,p) -> line.getData().add(new XYChart.Data<>(d.toEpochDay(), p.doubleValue())));
        chart.getData().setAll(List.of(line));
        chart.setTitle("Clôtures quotidiennes de " + data.symbol());
    }
    private void trade(boolean buy) {
        String ticker = symbol.getText();
        BigDecimal amount = decimal(quantity,"Quantité");
        run("Enregistrement…", () -> service.trade(ticker,amount,buy),
                data -> { invalidateResults(); displayQuote(data); displayBook(); refresh(false, "Opération enregistrée."); });
    }
    private void preview() {
        String ticker = MarketDataApi.symbol(symbol.getText());
        BigDecimal amount = decimal(quantity,"Quantité");
        run("Calcul du scénario…", () -> service.preview(ticker,amount), s -> {
            scenario.setText("Poids de " + ticker + " : " + percent(s.before().weights().getOrDefault(ticker,0d))
                    + " → " + percent(s.after().weights().get(ticker)) + "    |    Liquidités après achat : " + money(s.cashAfter())
                    + " USD\nVolatilité annualisée : " + percent(s.riskBefore().volatility()) + " → "
                    + percent(s.report().risk().volatility()) + "\nVolatilité annuelle du titre : " + percent(s.assetVolatility())
                    + " · variation du dernier cours : " + percent(s.lastReturn()) + "\n" + String.join("\n", s.report().advice()));
        });
    }

    private Node analytics() {
        configureDates((NumberAxis) valuationChart.getXAxis());
        ((NumberAxis) valuationChart.getYAxis()).setForceZeroInRange(false);
        valuationChart.setTitle("Valeur historique de la composition actuelle (USD)");
        valuationChart.setCreateSymbols(false);
        valuationChart.setAnimated(false);
        valuationChart.setLegendVisible(false);
        valuationChart.setPrefHeight(220);
        profile.getItems().setAll(Portfolio.Profile.values());
        profile.setValue(service.portfolio().profile());
        var save = button("Appliquer le profil", () -> {
            var choice = profile.getValue();
            run("Enregistrement du profil…", () -> { service.profile(choice); return choice; },
                    p -> { invalidateResults(); message("Profil enregistré : " + p, false); });
        });
        var compute = button("Calculer les indicateurs", () -> run("Analyse des séances communes…", service::report, r -> {
            StringBuilder sb = new StringBuilder(period(r.sample())).append("\n\n")
                    .append(riskText(r.risk())).append("\n\n");
            for (int i = 0; i < r.sample().symbols().size(); i++) {
                sb.append(r.sample().symbols().get(i)).append(" · rendement moyen / séance ")
                  .append(percent(r.sample().mean()[i])).append(" · volatilité annuelle ")
                  .append(percent(Math.sqrt(252 * r.sample().covariance()[i][i]))).append("\n");
                for (int j = 0; j < i; j++) sb.append("    Corrélation avec ").append(r.sample().symbols().get(j))
                        .append(" : ").append(number(r.sample().correlation(i,j))).append("\n");
            }
            var line = new XYChart.Series<Number,Number>();
            r.historicalValues().forEach((d,v) -> line.getData().add(new XYChart.Data<>(d.toEpochDay(),v)));
            valuationChart.getData().setAll(List.of(line));
            analysis.setText(sb.append("\n").append(String.join("\n",r.advice())).toString());
        }));
        VBox.setVgrow(analysis, Priority.ALWAYS);
        analysis.setPromptText("Les calculs utilisent les positions actuelles et leurs clôtures historiques.");
        return page(row(new Label("Profil"),profile,save,compute),analysis,valuationChart);
    }
    private static String riskText(AnalysisService.Risk r) {
        return "Rendement moyen annualisé : " + percent(r.annualReturn()) + "\nVolatilité annualisée : "
                + percent(r.volatility()) + "\nRatio de Sharpe (taux sans risque nul) : " + number(r.sharpe());
    }
    private static String period(AnalysisService.Sample s) {
        return s.observations() + " rendements communs · du " + s.start() + " au " + s.end();
    }
    private Node optimization() {
        selection.setPromptText("Ex. IBM, MSFT, SPY");
        cap.setPrefColumnCount(5); target.setPrefColumnCount(5);
        var compute = button("Calculer l’allocation", () -> {
            List<String> symbols = selection.getText().isBlank()
                    ? service.portfolio().assets().stream().map(AssetPosition::symbol).toList()
                    : List.of(selection.getText().trim().split("[,;\\s]+"));
            double limit = decimal(cap,"Plafond").doubleValue()/100;
            Double floor = target.getText().isBlank() ? null : decimal(target,"Cible").doubleValue()/100;
            run("Recherche de l’allocation de variance minimale…", () -> service.optimize(symbols,limit,floor), p -> {
                var sb = new StringBuilder(period(p.sample())).append("\n\nPoids du capital total · actuel → proposé\n");
                p.allocation().weights().forEach((k,w) -> sb.append(k).append("    ")
                        .append(percent(p.actual().getOrDefault(k,0d))).append(" → ").append(percent(w)).append("\n"));
                proposal.setText(sb.append("\n").append(riskText(p.allocation().risk())).toString());
            });
        });
        compute.getStyleClass().add("primary");
        VBox.setVgrow(proposal, Priority.ALWAYS);
        var note = new Label("Le modèle répartit 100 % du capital entre les titres sélectionnés. Une sélection vide reprend les positions détenues.");
        note.setWrapText(true);
        return page(note,row(new Label("Symboles"),selection),
                row(new Label("Plafond par titre (%)"),cap,new Label("Rendement annuel minimal (%)"),target),
                compute,proposal);
    }

    private void invalidateResults() { analysis.clear(); proposal.clear(); scenario.clear(); valuationChart.getData().clear(); }
    private void displayBook() {
        var p = service.portfolio();
        positions.getItems().setAll(p.assets());
        var trades = new ArrayList<>(p.trades());
        Collections.reverse(trades);
        journal.getItems().setAll(trades);
        summary.setText("Liquidités : " + money(p.cash()) + " USD");
    }
    public void refresh(boolean force) { refresh(force, "Portefeuille à jour."); }
    private void refresh(boolean force, String success) {
        if (force) { watched = null; invalidateResults(); chart.getData().clear(); quote.setText(""); }
        market = Map.of(); positions.refresh(); distribution.getData().clear(); displayBook();
        run("Valorisation des positions…", () -> service.view(force), view -> {
            market = view.market();
            positions.refresh();
            var v = view.valuation();
            summary.setText("Valeur : " + money(v.total()) + " USD     Liquidités : " + money(view.portfolio().cash())
                    + " USD     Gain/perte : " + money(v.profit()) + " USD (" + percent(v.performance()) + ")");
            v.byType().forEach((k,w) -> { if (w > 0) distribution.getData().add(new PieChart.Data(k + " · " + percent(w),w)); });
            message(success,false);
        });
    }

    private void message(String text, boolean error) {
        status.setText(text == null ? "Opération impossible." : text);
        status.getStyleClass().remove("error");
        if (error) status.getStyleClass().add("error");
    }
    private <T> void run(String label, Callable<T> operation, Consumer<T> display) {
        tabs.setDisable(true); message(label,false);
        Task<T> task = new Task<>() { @Override protected T call() throws Exception { return operation.call(); } };
        task.setOnSucceeded(e -> {
            tabs.setDisable(false);
            message("Terminé.",false);
            display.accept(task.getValue());
        });
        task.setOnFailed(e -> { tabs.setDisable(false); message(task.getException().getMessage(),true); });
        executor.execute(task);
    }
}
