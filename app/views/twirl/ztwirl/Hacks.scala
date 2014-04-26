
package x 
  
  object SmpServices {
    val MobileTelephony = "MobileTelephony"
    val MobileDataCommon = "MobileDataCommon"
  }
	
  object SmpResources {
    val MobileSIM = "MobileSIM" 
  }

case class FulfillmentOrderItem (action:String, entity:SEntity) {
  def getManagedEntity() = entity
  
}

case class SEntity (t:String, spec:String, parms:Map[String,String]) {
  def getAttributeValue(s:String) = parms(s)
}

object reqItems {
  def get(s:String) = new FulfillmentOrderItem("add", SEntity("Service", "email", Map()))
}

object y {
  // make a SSV external key from the FO item entity
  def ssvextkey(implicit item:FulfillmentOrderItem) = "1"
    
  def empty(item:FulfillmentOrderItem) : String = ""
    
  def parentKey(implicit item:FulfillmentOrderItem) = "1"
    
  def lineItemKey = "x"
}

object context {
  object subscriber {
    def getRelatedEntity(e:SEntity) = e
  }
}