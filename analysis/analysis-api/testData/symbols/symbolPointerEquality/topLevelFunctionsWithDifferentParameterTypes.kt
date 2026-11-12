// Control case: overloads which differ in their rendered parameter types are distinguished correctly.
package test

interface A
interface B

fun g(x: A, y: B) {}

fun g(x: B, y: A) {}
