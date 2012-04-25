package model

object Api {
  def findUser(email: String): Option[User] = Some(User("Andrei", "Cojocaru", email))
  def findUsers(something: String): List[User] = List(User("Andrei", "Cojocaru", "a@b.com"))
  def createOrFindUser(email: String): Option[User] = Some(User("Andrei", "Cojocaru", email))
}

