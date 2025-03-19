package su.nepom.budget.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun <T> ioOp(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }