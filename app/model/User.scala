package model

case class User(firstName: String, lastName: String, email:String) {
  def ename = if (firstName != null && firstName.size > 0) firstName else email.replaceAll("@.*", "")
}
