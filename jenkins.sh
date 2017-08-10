export GOPATH=/root/.jenkins/workspace
GOSRC_DIR=/root/.jenkins/workspace/src
ONEOAAS_ROOT=/root/.jenkins/workspace/src/oneoaas.com
PROJECT_DIR=Monitor


echo "step1 working current is :" `pwd`

if [ ! -d $GOSRC_DIR ]; then
  echo "$GOSRC_DIR not have ...."
  mkdir -p $GOSRC_DIR
else
  echo "$GOSRC_DIR is have ...."
  rm -rf $GOSRC_DIR
  mkdir -p $GOSRC_DIR
fi

if [ ! -d $ONEOAAS_ROOT ]; then
  echo "$ONEOAAS_ROOT not have ...."
  mkdir -p $ONEOAAS_ROOT
else
  echo "$ONEOAAS_ROOT is have ...."
  rm -rf $ONEOAAS_ROOT/$PROJECT_DIR
fi


echo "step2 going workspace dir "
cd $GOPATH


echo "step3 mv $PROJECT_DIR  to $ONEOAAS_ROOT"
mv $PROJECT_DIR $ONEOAAS_ROOT

echo "step4 working current is $ONEOAAS_ROOT"
cd $ONEOAAS_ROOT/$PROJECT_DIR

echo "chmod +x to build_release.sh start ..."
chmod +x build/*.sh
echo "chmod +x to build_release.sh end ..."

echo "cp vendor pkg to gopath src start..."
cp -rf vendor/* $GOSRC_DIR
echo "cp vendor pkg to gopath src finish..."

echo "step5 start building ....."
make release
echo "step6 finish building ....."


echo "step7 rpm build start  ....."
make rpm
echo "step7 rpm build end  ....."

#mv $ONEOAAS_ROOT/$PROJECT_DIR $GOPATH

#echo "happy build cmdb thanks!!!!!!!!"
