// Non-PSI pointers of these overloads must not be confused: their parameters are rendered identically (`T`, `U`),
// but the type parameter bounds differ, so they are different (and legal) overloads.
package test

interface A
interface B

class C {
    fun <T : A, U : B> m(x: T, y: U) {}

    fun <U : A, T : B> m(x: T, y: U) {}
}
