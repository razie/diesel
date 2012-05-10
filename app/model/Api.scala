package model

trait Api {
  def findUser(email: String): Option[User]
  def findUsers(something: Option[String]=None): List[User]
  def createUser(u: User): Option[User]
}

object Api extends Api {
  lazy val impl: Api = TestImpl

  override def findUser(email: String): Option[User] = impl.findUser(email)
  override def findUsers(something: Option[String]): List[User] = impl.findUsers(something)
  override def createUser(u:User): Option[User] = impl.createUser(u)
}

object TestImpl extends Api {
  override def findUser(email: String): Option[User] = Users.findUser(email)

  override def findUsers(something: Option[String]): List[User] = 
    List()
    
  override def createUser(u: User): Option[User] = {
    u.create(u.mkProfile)
    Some(u)
  }

}
