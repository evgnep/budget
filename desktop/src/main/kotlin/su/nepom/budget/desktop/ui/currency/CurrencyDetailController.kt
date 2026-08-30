package su.nepom.budget.desktop.ui.currency

import jakarta.inject.Inject
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.TextField
import su.nepom.budget.desktop.model.CurrencyObservable
import su.nepom.budget.desktop.service.CurrencyService
import su.nepom.budget.desktop.service.DbService
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.FormDriver
import su.nepom.budget.event.CurrencyContent
import java.net.URL
import java.util.*

// TODO detail (edit) part of the currency form, split out so it can be reused in other forms
@Suppress("unused")
class CurrencyDetailController @Inject constructor(
    private val dbService: DbService,
    private val currencyService: CurrencyService,
) : Controller, Initializable {

    @FXML
    private lateinit var idTextField: TextField

    @FXML
    private lateinit var hiddenCheckbox: CheckBox

    @FXML
    private lateinit var cancelButton: Button

    @FXML
    private lateinit var okButton: Button

    @FXML
    private lateinit var digitsAfterPointTextField: TextField

    @FXML
    private lateinit var codeTextField: TextField

    @FXML
    private lateinit var nameTextField: TextField

    lateinit var formDriver: FormDriver<*, CurrencyObservable>
        private set

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        formDriver = FormDriver.builder(
            okButton,
            cancelButton,
            currencyService.currencyFactory,
            dbService.sessionProperty)
            .idField(idTextField)
            .field(
                "name",
                nameTextField,
                nameTextField.textProperty(),
                { it?.content?.name ?: "" },
                { name = it }) {
                withMethod {
                    if (nameTextField.text.isEmpty()) it.error(" Название не должно быть пустым")
                }.immediateClear()
            }
            .field(
                "officialCode",
                codeTextField,
                codeTextField.textProperty(),
                { it?.content?.officialCode ?: "" },
                { officialCode = it }) {
                withMethod {
                    if (codeTextField.text.isEmpty()) it.error("Код не должен быть пустым")
                }.immediateClear()
            }
            .field(
                "digitsAfterPoint",
                digitsAfterPointTextField,
                digitsAfterPointTextField.textProperty(),
                { it?.content?.digitsAfterPoint?.toString() ?: "2" },
                { digitsAfterPoint = it.toInt() },
                disabledInEditMode = true) {
                withMethod {
                    val value = digitsAfterPointTextField.text.toIntOrNull()
                    if (value == null || value < 0 || value > 9)
                        it.error("Число знаков после запятой должно быть от 0 до 9")
                }.immediateClear()
            }
            .field(
                "hidden",
                hiddenCheckbox,
                hiddenCheckbox.selectedProperty(),
                { it?.content?.hidden ?: false },
                { hidden = it })
            .build()
    }

    // TODO show a past version of a currency (from the history form), view only
    fun showReadOnly(content: CurrencyContent) {
        formDriver.showReadOnly(CurrencyObservable(content))
    }
}
