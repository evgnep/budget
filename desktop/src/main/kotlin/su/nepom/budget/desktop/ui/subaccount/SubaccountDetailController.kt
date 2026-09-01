package su.nepom.budget.desktop.ui.subaccount

import jakarta.inject.Inject
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.TextField
import su.nepom.budget.desktop.model.SubaccountObservable
import su.nepom.budget.desktop.service.DbService
import su.nepom.budget.desktop.util.fx.Controller
import su.nepom.budget.desktop.util.fx.FormDriver
import su.nepom.budget.model.AccountId
import su.nepom.budget.model.RawMoney
import su.nepom.budget.utils.format
import su.nepom.budget.utils.toRawMoneyOrNull
import java.net.URL
import java.util.*

@Suppress("unused")
class SubaccountDetailController @Inject constructor(
    private val dbService: DbService,
) : Controller, Initializable {

    private val factory = SubaccountObservable.Factory()

    // the currency of the parent account decides how the rest field is parsed/formatted
    private var digitsAfterPoint = 2

    @FXML
    private lateinit var nameTextField: TextField

    @FXML
    private lateinit var restTextField: TextField

    @FXML
    private lateinit var hiddenCheckbox: CheckBox

    @FXML
    private lateinit var cancelButton: Button

    @FXML
    private lateinit var okButton: Button

    lateinit var formDriver: FormDriver<*, SubaccountObservable>
        private set

    // called by the owning screen once it knows the account, after this controller's own initialize()
    fun configure(accountId: AccountId, digitsAfterPoint: Int) {
        factory.accountId = accountId
        this.digitsAfterPoint = digitsAfterPoint
    }

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        formDriver = FormDriver.builder(okButton, cancelButton, factory, dbService.sessionProperty)
            .field(
                "name",
                nameTextField,
                nameTextField.textProperty(),
                { it?.content?.name ?: "" },
                { name = it }) {
                withMethod {
                    if (nameTextField.text.isBlank()) it.error("Название не должно быть пустым")
                }.immediateClear()
            }
            .field(
                "rest",
                restTextField,
                restTextField.textProperty(),
                { it?.content?.rest?.format(digitsAfterPoint) ?: RawMoney.ZERO.format(digitsAfterPoint) },
                { rest = restTextField.text.toRawMoneyOrNull(digitsAfterPoint) ?: RawMoney.ZERO }) {
                withMethod {
                    if (restTextField.text.toRawMoneyOrNull(digitsAfterPoint) == null) it.error("Некорректная сумма")
                }.immediateClear()
            }
            .field(
                "hidden",
                hiddenCheckbox,
                hiddenCheckbox.selectedProperty(),
                { it?.content?.isHidden ?: false },
                { hidden = it })
            .build()
    }
}
