<%@ page contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="s" uri="/struts-tags" %>
<!DOCTYPE html>
<html><head><title>Warranty claims</title>
<style>body{font:13px Verdana,sans-serif;margin:20px}table{border-collapse:collapse}td,th{border:1px solid #999;padding:4px 8px}</style>
</head><body><h2>Warranty claims</h2>
<s:actionerror/>
<s:form action="saveClaim" method="post">
<s:textfield name="dealerCode" label="Dealer"/>
<s:textfield name="modelCode" label="Model"/>
<s:textfield name="serialNo" label="Serial number"/>
<s:textfield name="claimType" label="Type (S, E or G)"/>
<s:textfield name="purchaseDate" label="Purchase date"/>
<s:textfield name="failureDate" label="Failure date"/>
<s:textfield name="meterHours" label="Meter hours"/>
<s:textfield name="repairCode" label="Repair code"/>
<s:textfield name="laborHours" label="Labor hours"/>
<s:textfield name="travelMiles" label="Travel miles"/>
<s:textfield name="partNo" label="Part number"/>
<s:textfield name="qty" label="Quantity"/>
<s:textfield name="unitCost" label="Unit cost"/>
<s:textarea name="notes" label="Repair notes"/>
<s:submit value="File claim"/>
</s:form></body></html>
