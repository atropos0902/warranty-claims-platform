# Oracle Database Free with a smaller memory footprint, so both systems fit on a laptop.
FROM gvenzl/oracle-free:23.9-slim
RUN printf '%s\n' "create pfile='/tmp/p.ora' from spfile;" exit | sqlplus -s / as sysdba \
 && sed -i -E '/sga_(max_size|target)|pga_aggregate|\.processes|__/d' /tmp/p.ora \
 && printf '%s\n' "*.sga_target=800m" "*.pga_aggregate_target=200m" "*.processes=150" >> /tmp/p.ora \
 && printf '%s\n' "create spfile from pfile='/tmp/p.ora';" exit | sqlplus -s / as sysdba
COPY *.sql /opt/claims-db/
COPY 00_run.sh /container-entrypoint-initdb.d/
