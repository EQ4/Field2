#!/bin/bash
pushd $(dirname `which "$0"`) >/dev/null; fieldhome="$PWD"; popd >/dev/null
echo $fieldhome

out=$fieldhome/out/production/

echo $fieldhome $out

/usr/lib/jvm/jdk1.8.0_20/bin/java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005 -Xmx4g -Xms4g -javaagent:$fieldhome/out/artifacts/fieldagent_jar/fieldagent.jar -cp $fieldhome/out/artifacts/fieldagent_jar/fieldagent.jar:$out/fieldwork2/*:$out/fieldwork2/:$out/fielded/:$out/fielded/*:$out/fieldbox/:$out/fieldbox/*:$out/fieldnashorn/*:$out/fieldnashorn/ -Djava.library.path=$out/fieldbox/ ${*}