<%@ page contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="s" uri="/struts-tags" %>
<!DOCTYPE html>
<html><head><title>Warranty claims</title>
<style>body{font:13px Verdana,sans-serif;margin:20px}table{border-collapse:collapse}td,th{border:1px solid #999;padding:4px 8px}</style>
</head><body><h2>Warranty claims</h2>
<p><a href="newClaim.action">File a claim</a></p>
<table><tr><th>Claim</th><th>Dealer</th><th>Model</th><th>Repair</th><th>Status</th><th>Total</th></tr>
<s:iterator value="claims"><tr>
<td><s:url var="u" action="claim"><s:param name="claimNo" value="claimNo"/></s:url><a href="<s:property value='#u'/>"><s:property value="claimNo"/></a></td>
<td><s:property value="dealerCode"/></td><td><s:property value="modelCode"/></td>
<td><s:property value="repairCode"/></td><td><s:property value="status"/></td><td><s:property value="totalPay"/></td>
</tr></s:iterator></table></body></html>
