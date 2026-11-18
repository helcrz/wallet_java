package fr.insa.portefeuille;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.geometry.Insets;
import javafx.beans.binding.Bindings;
import java.util.Optional;
import java.nio.file.Files;
import javafx.stage.Stage;
import java.math.BigDecimal;
import java.nio.file.Path;

public final class MainApp extends Application {
    private PortfolioService service;
    private DashboardController dashboard;

    @Override public void start(Stage stage) {
        try {
            var api = MarketDataApi.configured();
            Path file = Path.of(System.getProperty("user.home"), ".wallet-java",
                    api.demo() ? "demo.json" : "portefeuille.json");
            Portfolio initial = Portfolio.create("Mon portefeuille", new BigDecimal("10000"));
            if (Files.notExists(file)) {
                var chosen = newPortfolio(api.demo());
                if (chosen.isEmpty()) { javafx.application.Platform.exit(); return; }
                initial = chosen.get();
            }
            service = new PortfolioService(file, api, initial);
            dashboard = new DashboardController(service);
            Scene scene = new Scene(dashboard.root(), 1100, 735);
            scene.getStylesheets().add(getClass().getResource("dashboard.css").toExternalForm());
            stage.setTitle("Atelier portefeuille");
            stage.setMinWidth(790);
            stage.setMinHeight(600);
            stage.setScene(scene);
            stage.setOnCloseRequest(e -> { if (dashboard.busy()) e.consume(); });
            stage.show();
            dashboard.refresh(false);
        } catch (Exception e) {
            new Alert(Alert.AlertType.ERROR, e.getMessage()).showAndWait();
            javafx.application.Platform.exit();
        }
    }
    private Optional<Portfolio> newPortfolio(boolean demo) {
        var dialog = new Dialog<Portfolio>();
        dialog.setTitle("Créer un portefeuille");
        dialog.setHeaderText(demo ? "Ouvrir un portefeuille de démonstration" : "Ouvrir un portefeuille aux cours de marché");
        var name = new TextField("Mon portefeuille");
        var capital = new TextField("10000");
        var form = new GridPane();
        form.setHgap(12); form.setVgap(10); form.setPadding(new Insets(14));
        form.addRow(0,new Label("Nom du portefeuille"),name);
        form.addRow(1,new Label("Capital initial en USD"),capital);
        dialog.getDialogPane().setContent(form);
        var create = new ButtonType("Créer",ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(create,ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(create).disableProperty().bind(Bindings.createBooleanBinding(() -> {
            String amount = capital.getText().trim().replace(',', '.');
            return name.getText().isBlank() || !amount.matches("[0-9]{1,12}([.][0-9]{1,2})?")
                    || new BigDecimal(amount).signum() <= 0;
        },name.textProperty(),capital.textProperty()));
        dialog.setResultConverter(button -> button == create ? Portfolio.create(name.getText().trim(),
                new BigDecimal(capital.getText().trim().replace(',', '.'))) : null);
        return dialog.showAndWait();
    }

    @Override public void stop() throws Exception {
        if (dashboard != null) dashboard.close();
        if (service != null) service.close();
    }
    public static void main(String[] args) { launch(args); }
}
