package app.health

import java.util.Arrays

/** Desktop-side check: the Kotlin port must keep the ECDH agreement and catalogs intact. */
object ProtocolCheck {
    @JvmStatic
    fun main(args: Array<String>) {
        val a = ByteArray(24)
        val b = ByteArray(24)
        java.security.SecureRandom().nextBytes(a)
        java.security.SecureRandom().nextBytes(b)
        val ap = ECDH_B163.ecdh_generate_public(a)
        val bp = ECDH_B163.ecdh_generate_public(b)
        val ab = ECDH_B163.ecdh_generate_shared(a, bp!!)
        val ba = ECDH_B163.ecdh_generate_shared(b, ap!!)
        if (ab == null || !Arrays.equals(ab, ba)) throw AssertionError("ECDH mismatch")
        check(java.util.HashSet(Arrays.asList(*Workouts.TYPES)).size == Workouts.TYPES.size) { "Workout duplicates" }
        check(FoodCatalog.FOODS.size >= 400) { "Food catalog too small" }
        check(Workouts.gps("Велосипед") && !Workouts.gps("Йога")) { "GPS selection" }
        println(
            "PASS: ECDH shared-key agreement, workout types=" + Workouts.TYPES.size +
                ", food names=" + FoodCatalog.FOODS.size + ", outdoor/indoor routing"
        )
    }
}
