package fr.insa.portefeuille;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="wallet.ui", matches="true")
class DashboardTest {
    @TempDir Path dir;
    static <T> T fx(Callable<T> action) throws Exception {
        var future = new FutureTask<>(action);
        Platform.runLater(future);
        return future.get(10,TimeUnit.SECONDS);
    }
    static void click(DashboardController c, String name) throws Exception {
        fx(() -> {
            var button = c.root().lookupAll(".button").stream().filter(Button.class::isInstance)
                    .map(Button.class::cast).filter(b -> name.equals(b.getText())).findFirst().orElseThrow();
            button.fire();
            return null;
        });
        await(c);
    }
    static void await(DashboardController c) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        while (fx(c::busy)) {
            if (System.nanoTime() > deadline) fail("L’interface reste occupée.");
            Thread.sleep(25);
        }
        assertFalse(fx(() -> c.root().getBottom().getStyleClass().contains("error")),
                () -> "Échec d’une action dans l’interface.");
    }
    static void select(DashboardController c, int tab) throws Exception {
        fx(() -> { ((TabPane)c.root().getCenter()).getSelectionModel().select(tab); return null; });
    }
    @Test void demoWorkflow() throws Exception {
        var ready = new CountDownLatch(1);
        Platform.startup(ready::countDown);
        assertTrue(ready.await(10,TimeUnit.SECONDS));
        var stage = new AtomicReference<Stage>();
        try (var service = new PortfolioService(dir.resolve("demo.json"),new MarketDataApi(""),
                Portfolio.create("Test", new BigDecimal("10000")))) {
            var controller = fx(() -> {
                var c = new DashboardController(service);
                var window = new Stage();
                stage.set(window);
                var scene = new Scene(c.root(),1100,735);
                scene.getStylesheets().add(MainApp.class.getResource("dashboard.css").toExternalForm());
                window.setScene(scene);
                window.show();
                c.refresh(false);
                return c;
            });
            try {
                await(controller);
                select(controller,1);
                click(controller,"Choisir un titre");
                fx(() -> {
                    var picker = javafx.stage.Window.getWindows().stream().filter(Stage.class::isInstance)
                            .map(Stage.class::cast).filter(s -> "Choisir un titre".equals(s.getTitle()))
                            .findFirst().orElseThrow();
                    var pane = (DialogPane)picker.getScene().getRoot();
                    var filter = (TextField)pane.lookup(".text-field");
                    var table = (TableView<?>)pane.lookup(".table-view");
                    assertEquals(5,table.getItems().size());
                    filter.setText("sans correspondance");
                    assertTrue(table.getItems().isEmpty());
                    filter.setText("aPpLe");
                    assertEquals(1,table.getItems().size());
                    var ok = pane.getButtonTypes().stream().filter(b -> b.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                            .findFirst().orElseThrow();
                    ((Button)pane.lookupButton(ok)).fire();
                    return null;
                });
                await(controller);
                click(controller,"Étudier l’achat");
                assertTrue(service.portfolio().assets().isEmpty());
                click(controller,"Acheter");
                assertEquals(1,service.portfolio().assets().size());
                assertEquals("AAPL",service.portfolio().assets().getFirst().symbol());
                select(controller,2);
                click(controller,"Calculer les indicateurs");
                select(controller,3);
                click(controller,"Calculer l’allocation");
                select(controller,1);
                click(controller,"Vendre");
                assertTrue(service.portfolio().assets().isEmpty());
                assertEquals(2,service.portfolio().trades().size());
            } finally { fx(() -> { controller.close(); stage.get().close(); return null; }); }
        } finally { Platform.exit(); }
    }
}
