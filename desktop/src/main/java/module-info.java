module su.nepom.budget.desktop {
    requires javafx.controls;
    requires javafx.fxml;
    requires kotlin.stdlib;

    requires org.controlsfx.controls;

    opens su.nepom.budget.desktop to javafx.fxml;
    exports su.nepom.budget.desktop;
}