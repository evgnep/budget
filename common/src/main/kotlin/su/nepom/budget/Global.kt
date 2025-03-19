package su.nepom.budget

import su.nepom.budget.model.Place

object Global {
    private var currentUserHolder = "unknown"

    fun setCurrentUser(name: String) {
        currentUserHolder = name
    }

    val currentUser: String get() = currentUserHolder

    private var currentPlaceHolder = Place.NULL

    fun setCurrentPlace(place: Place) {
        currentPlaceHolder = place
    }

    val currentPlace: Place get() = currentPlaceHolder
}