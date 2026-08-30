package su.nepom.budget.desktop.util.fx

import javafx.scene.image.Image
import javafx.stage.Stage

private val iconsSizes = listOf(16, 24, 32, 48, 64, 128, 256)

fun Stage.setIcon(icon: String) {
  iconsSizes.forEach { size ->
    icons.add(Image(this::class.java.getResourceAsStream("/icons/$icon-$size.png")))
  }
}