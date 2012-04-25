package model

case class Registration(email:String, password:String) {
  def ename = email.replaceAll("@.*", "")
}