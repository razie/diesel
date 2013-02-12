if true then
scala <<EOF
"Hallo"
exit
EOF
else
scalac <<EOF
"what's up"
exit
EOF
fi

