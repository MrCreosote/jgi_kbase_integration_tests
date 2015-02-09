#!/usr/bin/env python
'''
Created on Aug 30, 2013

@author: gaprice@lbl.gov
'''
from configobj import ConfigObj
import os
import sys

ANT = 'ant'

CFG_SECTION = 'JGIIntTest'

CONFIG_OPTS = ['test.jgi.user',
               'test.jgi.pwd',
               'test.kbase.jgi.gmail.user',
               'test.kbase.jgi.gmail.pwd',
               'test.kbase.user1',
               'test.kbase.pwd1',
               'test.kbase.user2',
               'test.kbase.pwd2',
               'test.kbase.wipe_user',
               'test.kbase.wipe_pwd',
               ]


def write_runner(out, ant_target):
    with open(out, 'w') as run:
        run.write('# Generated file - do not check into git\n')
#        run.write('cd ..\n')
        run.write(ANT + ' ' + ant_target)
        for o in CONFIG_OPTS:
            if o in testcfg:
                run.write(' -D' + o + '=' + testcfg[o])
        run.write('\n')
    os.chmod(out, 0755)
    print 'Writing test runner with target "' + ant_target + '" to: ' + out


if __name__ == '__main__':
    d, _ = os.path.split(os.path.abspath(__file__))
    fn = 'test.cfg'
    if len(sys.argv) > 1:
        fn = sys.argv[1]
    fn = os.path.join(d, fn)
    if not os.path.isfile(fn):
        print 'No such config file ' + fn + '. Halting.'
        sys.exit(1)
    print 'Using test config file ' + fn
    out_run_tests = os.path.join(d, 'run_tests.sh')
    cfg = ConfigObj(fn)
    try:
        testcfg = cfg[CFG_SECTION]
    except KeyError as ke:
        print 'Test config file ' + fn + ' is missing section ' +\
            CFG_SECTION + '. Halting.'
        sys.exit(1)
    if testcfg['test.kbase.user1'] == testcfg['test.kbase.user2']:
        print "The two test users are identical. Halting."
        sys.exit(1)
    write_runner(out_run_tests, 'test')
