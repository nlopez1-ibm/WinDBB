# Sample test script to package a DBB Build  for staging into artifactory or Code Station

# The Jenkinsfile in this folder shows how to implement this script in Jenkins
# Jenkins will use ortqq/application-conf/package.properties not the environment variable shown here
clear

# makes sure there are no trailing spaces after the \

groovyz $HOME/Garanti-zAppBuild/package.groovy\
  -w /u/nlopez/Garanti-logs/build.20191110.100327.003\
  -a ortqq\
  -v "1.0.0"\
  -s gitSourceUrl-TBD\
  -g git@github.ibm.com:Nelson-Lopez1/ortqq.git\
  -x gitSourceBranch-TBD\
  -y gitBuildBranch-TBD\
  -b 12345678\
  -n P092259\
  -u ARTIFACTORY.COM



