<%@ page contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="s" uri="/struts-tags" %>
<!DOCTYPE html>
<html><head><title>Warranty claims</title>
<style>body{font:13px Verdana,sans-serif;margin:20px}table{border-collapse:collapse}td,th{border:1px solid #999;padding:4px 8px}</style>
</head><body><h2>Warranty claims</h2>
<table>
<s:iterator value="claim">
<tr><th>Claim</th><td><s:property value="claimNo"/></td></tr>
<tr><th>Dealer</th><td><s:property value="dealerCode"/></td></tr>
<tr><th>Repair</th><td><s:property value="repairCode"/></td></tr>
<tr><th>Labor hours</th><td><s:property value="laborHours"/></td></tr>
<tr><th>Coverage %</th><td><s:property value="coveragePct"/></td></tr>
<tr><th>Labor</th><td><s:property value="laborPay"/></td></tr>
<tr><th>Parts</th><td><s:property value="partsPay"/></td></tr>
<tr><th>Travel</th><td><s:property value="travelPay"/></td></tr>
<tr><th>Total</th><td><s:property value="totalPay"/></td></tr>
<tr><th>Status</th><td><s:property value="status"/></td></tr>
<tr><th>Notes</th><td><s:property value="notes"/></td></tr>
</s:iterator></table><p><a href="claims.action">All claims</a></p></body></html>
